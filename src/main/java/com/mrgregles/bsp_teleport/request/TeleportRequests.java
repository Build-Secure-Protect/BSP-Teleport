package com.mrgregles.bsp_teleport.request;

import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.network.Message;
import com.mrgregles.bsp_teleport.network.Messenger;
import com.mrgregles.bsp_teleport.network.PlayerDirectory;
import com.mrgregles.bsp_teleport.teleport.TeleportKind;
import com.mrgregles.bsp_teleport.teleport.Teleports;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Player-to-player requests: {@code /tpa} (I come to you) and {@code /tpahere} (you come to me), on this server or
 * across the network.
 *
 * <p>One open request per target; a newer one replaces it. Positions are snapshots: a {@code /tpahere} lands where the
 * requester stood when they asked, a {@code /tpa} where the target stood when they accepted. That is what makes the
 * cross-server case a single message each way.
 */
public final class TeleportRequests {
    /** Keyed by the target's name, lower case. */
    private static final Map<String, Request> OPEN = new HashMap<>();

    private record Request(String fromName, String fromServer, Position fromPosition, boolean here, long expiresAt) {
        boolean local() {
            return fromServer.equals(TeleportConfig.serverName());
        }
    }

    private TeleportRequests() {
    }

    // Sending

    public static void send(ServerPlayer from, String targetName, boolean here) {
        if (from.getGameProfile().getName().equalsIgnoreCase(targetName)) {
            tell(from, "request.self");
            return;
        }
        long expiry = System.currentTimeMillis() + TeleportConfig.getOr(TeleportConfig.REQUEST_SECONDS, 60) * 1000L;
        Request request = new Request(from.getGameProfile().getName(), TeleportConfig.serverName(), Position.of(from), here, expiry);
        ServerPlayer target = Messenger.find(from.server, targetName);
        if (target != null) {
            if (TeleportData.get(from.server).isIgnoring(target.getUUID())) {
                tell(from, "request.unknown_player", targetName);
                return;
            }
            receive(target, request);
            tell(from, "request.sent", target.getDisplayName());
            return;
        }
        String server = PlayerDirectory.serverOf(targetName);
        if (server == null) {
            tell(from, "request.unknown_player", targetName);
            return;
        }
        Messenger.send(new Message(Message.Type.TP_REQUEST, Message.To.PLAYER, targetName)
                .put("fromName", request.fromName()).put("fromPosition", request.fromPosition().serialize())
                .put("here", Boolean.toString(here)).put("expiresAt", Long.toString(expiry)));
        tell(from, "request.sent", Component.literal(targetName));
    }

    public static void onRemoteRequest(ServerPlayer target, Message m) {
        try {
            receive(target, new Request(m.get("fromName"), m.source, Position.parse(m.get("fromPosition")), m.flag("here"), Long.parseLong(m.get("expiresAt"))));
        } catch (RuntimeException ignored) {
            // malformed; drop
        }
    }

    /** {@code /tpaall}: asks everyone on the network to come to the sender. */
    public static void sendToAll(ServerPlayer from) {
        long expiry = System.currentTimeMillis() + TeleportConfig.getOr(TeleportConfig.REQUEST_SECONDS, 60) * 1000L;
        Request request = new Request(from.getGameProfile().getName(), TeleportConfig.serverName(), Position.of(from), true, expiry);
        int count = 0;
        for (ServerPlayer target : from.server.getPlayerList().getPlayers()) {
            if (target != from && !TeleportData.get(from.server).isIgnoring(target.getUUID())) {
                receive(target, request);
                count++;
            }
        }
        Messenger.send(new Message(Message.Type.TP_REQUEST, Message.To.ALL, Message.ALL)
                .put("fromName", request.fromName()).put("fromPosition", request.fromPosition().serialize())
                .put("here", "true").put("expiresAt", Long.toString(expiry)));
        tell(from, "request.sent_all", count);
    }

    /** {@code /tpignore}: flips whether the player receives requests. */
    public static boolean toggleIgnore(ServerPlayer player) {
        TeleportData data = TeleportData.get(player.server);
        boolean now = !data.isIgnoring(player.getUUID());
        data.setIgnoring(player.getUUID(), now);
        com.mrgregles.bsp_teleport.storage.Storage.ignoringSet(player.getUUID(), now);
        tell(player, now ? "request.ignoring_on" : "request.ignoring_off");
        return now;
    }

    private static void receive(ServerPlayer target, Request request) {
        if (TeleportData.get(target.server).isIgnoring(target.getUUID()) || request.fromName().equalsIgnoreCase(target.getGameProfile().getName())) {
            return;
        }
        OPEN.put(key(target), request);
        tell(target, request.here() ? "request.received_here" : "request.received", Component.literal(request.fromName()));
    }

    // Answering

    public static void accept(ServerPlayer target) {
        Request request = take(target);
        if (request == null) {
            return;
        }
        if (request.local()) {
            ServerPlayer from = Messenger.find(target.server, request.fromName());
            if (from == null) {
                tell(target, "request.offline", request.fromName());
                return;
            }
            tell(from, "request.accepted", target.getDisplayName());
            if (request.here()) {
                Teleports.start(target, Position.of(from).toDestination(""), from.getDisplayName(), TeleportKind.REQUEST, null);
            } else {
                Teleports.start(from, Position.of(target).toDestination(""), target.getDisplayName(), TeleportKind.REQUEST, null);
            }
            return;
        }
        boolean sent = Messenger.send(new Message(Message.Type.TP_RESPONSE, Message.To.PLAYER, request.fromName())
                .put("accepted", "true").put("here", Boolean.toString(request.here()))
                .put("recipientName", target.getGameProfile().getName()).put("recipientPosition", Position.of(target).serialize()));
        if (!sent) {
            tell(target, "request.offline", request.fromName());
            return;
        }
        if (request.here()) {
            Teleports.start(target, request.fromPosition().toDestination(""), Component.literal(request.fromName()), TeleportKind.REQUEST, null);
        }
    }

    public static void deny(ServerPlayer target) {
        Request request = take(target);
        if (request == null) {
            return;
        }
        tell(target, "request.denied_you", request.fromName());
        if (request.local()) {
            ServerPlayer from = Messenger.find(target.server, request.fromName());
            if (from != null) {
                tell(from, "request.denied", target.getDisplayName());
            }
        } else {
            Messenger.send(new Message(Message.Type.TP_RESPONSE, Message.To.PLAYER, request.fromName())
                    .put("accepted", "false").put("recipientName", target.getGameProfile().getName()));
        }
    }

    public static void onRemoteResponse(ServerPlayer from, Message m) {
        String recipient = m.get("recipientName");
        if (!m.flag("accepted")) {
            tell(from, "request.denied", Component.literal(recipient));
            return;
        }
        tell(from, "request.accepted", Component.literal(recipient));
        if (!m.flag("here")) {
            Position position = Position.parse(m.get("recipientPosition"));
            Teleports.start(from, position.toDestination(""), Component.literal(recipient), TeleportKind.REQUEST, null);
        }
    }

    /** The request waiting for a player, if any, for the band screen. */
    public record Open(String fromName, String fromServer, boolean here, int secondsLeft) {
    }

    public static java.util.Optional<Open> peek(ServerPlayer target) {
        Request r = OPEN.get(key(target));
        long left = r == null ? 0 : r.expiresAt() - System.currentTimeMillis();
        if (r == null || left <= 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new Open(r.fromName(), r.fromServer(), r.here(), (int) ((left + 999) / 1000)));
    }

    @Nullable
    private static Request take(ServerPlayer target) {
        Request request = OPEN.remove(key(target));
        if (request == null || request.expiresAt() < System.currentTimeMillis()) {
            tell(target, "request.none");
            return null;
        }
        return request;
    }

    private static String key(ServerPlayer player) {
        return player.getGameProfile().getName().toLowerCase(java.util.Locale.ROOT);
    }

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.getServer().getTickCount() % 100 == 0) {
            long now = System.currentTimeMillis();
            OPEN.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
        }
    }

    /** Names a player can send a request to: this server's and the network's. */
    public static java.util.List<String> suggestions(MinecraftServer server) {
        return PlayerDirectory.allNames(server);
    }

    private static void tell(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable("message.bsp_teleport." + key, args), false);
    }
}
