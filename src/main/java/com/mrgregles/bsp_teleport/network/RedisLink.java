package com.mrgregles.bsp_teleport.network;

import com.mrgregles.bsp_teleport.BSPTeleport;

import javax.annotation.Nullable;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A small Redis client: the RESP protocol over two sockets, one for publishing (and PING), one that stays subscribed.
 *
 * <p>Only AUTH, SELECT, PING, PUBLISH and SUBSCRIBE are spoken. No library is bundled, so nothing fights the hybrid
 * server's class loaders. Both sockets reconnect with a backoff when they drop; while Redis is away, publishes are
 * dropped and logged.
 */
public final class RedisLink implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final long RECONNECT_MS = 8000;

    private final String host, password, channel;
    private final int port, db;
    private final boolean ssl;
    private final Consumer<String> onMessage;

    @Nullable
    private volatile Socket pub;
    @Nullable
    private volatile OutputStream pubOut;
    @Nullable
    private volatile InputStream pubIn;
    @Nullable
    private volatile Socket sub;
    private volatile boolean running;
    private volatile boolean listening;
    private volatile String lastError = "";

    public RedisLink(String host, int port, String password, boolean ssl, int db, String channel, Consumer<String> onMessage) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.ssl = ssl;
        this.db = db;
        this.channel = channel;
        this.onMessage = onMessage;
    }

    public String describe() {
        return host + ":" + port + " channel " + channel + (listening ? " (listening)" : " (not listening" + (lastError.isEmpty() ? "" : ": " + lastError) + ")");
    }

    public boolean listening() {
        return listening;
    }

    // RESP

    /** Encodes a command as a RESP array of bulk strings. */
    public static byte[] encode(String... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("*" + parts.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        for (String p : parts) {
            byte[] b = p.getBytes(StandardCharsets.UTF_8);
            out.writeBytes(("$" + b.length + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.writeBytes(b);
            out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    /** Decodes one RESP value: String (simple, bulk or error prefixed with "-"), Long, List, or null. */
    @Nullable
    public static Object decode(InputStream in) throws IOException {
        int type = in.read();
        if (type < 0) {
            throw new IOException("Connection closed");
        }
        String line = readLine(in);
        switch (type) {
            case '+':
                return line;
            case '-':
                return "-" + line;
            case ':':
                return Long.parseLong(line);
            case '$': {
                int len = Integer.parseInt(line);
                if (len < 0) {
                    return null;
                }
                byte[] data = in.readNBytes(len);
                if (data.length != len) {
                    throw new IOException("Connection closed");
                }
                readLine(in);
                return new String(data, StandardCharsets.UTF_8);
            }
            case '*': {
                int count = Integer.parseInt(line);
                if (count < 0) {
                    return null;
                }
                List<Object> items = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    items.add(decode(in));
                }
                return items;
            }
            default:
                throw new IOException("Unexpected RESP type " + (char) type);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\r') {
                in.read();
                return out.toString(StandardCharsets.UTF_8);
            }
            out.write(b);
        }
        throw new IOException("Connection closed");
    }

    // Sockets

    private Socket open() throws IOException {
        SocketFactory f = ssl ? SSLSocketFactory.getDefault() : SocketFactory.getDefault();
        Socket s = f.createSocket();
        s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        s.setTcpNoDelay(true);
        OutputStream out = s.getOutputStream();
        InputStream in = new BufferedInputStream(s.getInputStream());
        if (!password.isEmpty()) {
            out.write(encode("AUTH", password));
            out.flush();
            expectOk(decode(in), "AUTH");
        }
        if (db != 0) {
            out.write(encode("SELECT", Integer.toString(db)));
            out.flush();
            expectOk(decode(in), "SELECT");
        }
        return s;
    }

    private static void expectOk(@Nullable Object reply, String command) throws IOException {
        if (!(reply instanceof String r) || r.startsWith("-")) {
            throw new IOException(command + " refused: " + reply);
        }
    }

    public String ping() throws IOException {
        try (Socket s = open()) {
            s.getOutputStream().write(encode("PING"));
            s.getOutputStream().flush();
            return String.valueOf(decode(new BufferedInputStream(s.getInputStream())));
        }
    }

    /**
     * B15, T80: the publishing socket belongs to one daemon thread. A caller (usually the server thread) only puts the
     * message in this queue and returns; the thread connects (at most once every 8 s while Redis is away), sends in order
     * and drops what has waited over 15 s. Nothing on the caller's thread ever waits for Redis.
     */
    private final java.util.concurrent.LinkedBlockingDeque<Queued> outbox = new java.util.concurrent.LinkedBlockingDeque<>(500);
    private volatile boolean lastAttemptFailed;
    @Nullable
    private Thread publisher;
    private static final long WAITING_MS = 15_000L;
    private static final int READ_TIMEOUT_MS = 3000;

    private record Queued(String message, long at) {
    }

    /** Queues a message for the channel. Never blocks; false once the last connection attempt has failed. */
    public boolean publish(String message) {
        Queued q = new Queued(message, System.currentTimeMillis());
        if (!outbox.offerLast(q)) {
            outbox.pollFirst();
            outbox.offerLast(q);
        }
        startPublisher();
        return !lastAttemptFailed;
    }

    private synchronized void startPublisher() {
        if (publisher != null && publisher.isAlive()) {
            return;
        }
        Thread t = new Thread(this::publishLoop, "BSP-Teleport redis publish");
        t.setDaemon(true);
        publisher = t;
        t.start();
    }

    private void publishLoop() {
        long nextAttempt = 0;
        while (running || !outbox.isEmpty()) {
            Queued q;
            try {
                q = outbox.pollFirst(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (q == null) {
                if (!running) {
                    return;
                }
                continue;
            }
            if (System.currentTimeMillis() - q.at() > WAITING_MS) {
                continue;
            }
            try {
                if (pub == null || pub.isClosed()) {
                    long now = System.currentTimeMillis();
                    if (now < nextAttempt) {
                        outbox.offerFirst(q);
                        Thread.sleep(Math.min(500, nextAttempt - now));
                        continue;
                    }
                    nextAttempt = now + RECONNECT_MS;
                    Socket s = open();
                    s.setSoTimeout(READ_TIMEOUT_MS);
                    pub = s;
                    pubOut = s.getOutputStream();
                    pubIn = new BufferedInputStream(s.getInputStream());
                    lastAttemptFailed = false;
                }
                pubOut.write(encode("PUBLISH", channel, q.message()));
                pubOut.flush();
                Object reply = decode(pubIn);
                if (reply instanceof String r && r.startsWith("-")) {
                    throw new IOException(r);
                }
            } catch (IOException | RuntimeException e) {
                // A RuntimeException here is close() taking the socket away mid-send; the message is tried again.
                lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                lastAttemptFailed = true;
                closeQuietly(pub);
                pub = null;
                outbox.offerFirst(q);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Starts the subscriber thread. */
    public void start() {
        running = true;
        Thread t = new Thread(this::subscribeLoop, "BSP-Teleport redis");
        t.setDaemon(true);
        t.start();
    }

    private void subscribeLoop() {
        boolean first = true;
        while (running) {
            try {
                Socket s = open();
                sub = s;
                s.getOutputStream().write(encode("SUBSCRIBE", channel));
                s.getOutputStream().flush();
                InputStream in = new BufferedInputStream(s.getInputStream());
                listening = true;
                lastError = "";
                if (first) {
                    BSPTeleport.LOGGER.info("Redis listening on {}:{} channel {}", host, port, channel);
                } else {
                    BSPTeleport.LOGGER.info("Redis connection is back");
                }
                first = false;
                while (running) {
                    Object reply = decode(in);
                    if (reply instanceof List<?> list && list.size() == 3 && "message".equals(list.get(0)) && list.get(2) instanceof String body) {
                        try {
                            onMessage.accept(body);
                        } catch (RuntimeException e) {
                            BSPTeleport.LOGGER.warn("Bad Redis message: {}", e.toString());
                        }
                    }
                }
            } catch (IOException e) {
                listening = false;
                lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                if (running && first) {
                    BSPTeleport.LOGGER.warn("Redis not reachable at {}:{} ({}); retrying every {} s", host, port, lastError, RECONNECT_MS / 1000);
                } else if (running) {
                    BSPTeleport.LOGGER.warn("Redis subscription lost ({}); retrying in {} s", lastError, RECONNECT_MS / 1000);
                }
            } finally {
                closeQuietly(sub);
                sub = null;
            }
            if (running) {
                try {
                    Thread.sleep(RECONNECT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        listening = false;
    }

    private static void closeQuietly(@Nullable Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        closeQuietly(sub);
        closeQuietly(pub);
        pub = null;
        listening = false;
    }
}
