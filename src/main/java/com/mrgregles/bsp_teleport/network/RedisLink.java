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
    private Socket pub;
    @Nullable
    private OutputStream pubOut;
    @Nullable
    private InputStream pubIn;
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

    /** Publishes on the link's channel. Safe from any thread; false when Redis is away. */
    public synchronized boolean publish(String message) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (pub == null || pub.isClosed() || pubOut == null || pubIn == null) {
                    pub = open();
                    pubOut = pub.getOutputStream();
                    pubIn = new BufferedInputStream(pub.getInputStream());
                }
                pubOut.write(encode("PUBLISH", channel, message));
                pubOut.flush();
                Object reply = decode(pubIn);
                if (reply instanceof String r && r.startsWith("-")) {
                    throw new IOException(r);
                }
                return true;
            } catch (IOException e) {
                lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                closeQuietly(pub);
                pub = null;
            }
        }
        BSPTeleport.LOGGER.warn("Redis publish failed: {}", lastError);
        return false;
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
