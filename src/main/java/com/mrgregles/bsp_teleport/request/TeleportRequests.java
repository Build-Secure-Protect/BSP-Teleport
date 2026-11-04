package com.mrgregles.bsp_teleport.request;

import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.network.Message;
import com.mrgregles.bsp_teleport.network.Messenger;
import com.mrgregles.bsp_teleport.network.PlayerDirectory;
import com.mrgregles.bsp_teleport.team.AllyTeleports;
import com.mrgregles.bsp_teleport.teleport.TeleportKind;
import com.mrgregles.bsp_teleport.teleport.Teleports;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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

    /**
     * @param allyTeam A-1: the asker's team when this is an ally asking to come (D168), else ""
     * @param fromId   the asker's uuid as text for an ally ask (the alliance is checked again on accept), else ""
     */
    private record Request(String fromName, String fromServer, Position fromPosition, boolean here, long expiresAt, String allyTeam, String fromId) {
        Request(String fromName, String fromServer, Position fromPosition, boolean here, long expiresAt) {
            this(fromName, fromServer, fromPosition, here, expiresAt, "", "");
        }

        boolean local() {
            return fromServer.equals(TeleportConfig.serverName());
        }

        boolean ally() {
            return !allyTeam.isEmpty();
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

    /**
     * A-1: an ally asks to come to {@code targetName}, on this server or another; the request waits
     * {@link AllyTeleports#ASK_SECONDS}. A player who ignores requests is not asked.
     */
    public static boolean sendAlly(ServerPlayer from, String targetName, String team) {
        long expiry = System.currentTimeMillis() + AllyTeleports.ASK_SECONDS * 1000L;
        Request request = new Request(from.getGameProfile().getName(), TeleportConfig.serverName(), Position.of(from), false, expiry,
                team.isEmpty() ? "?" : team, from.getUUID().toString());
        ServerPlayer target = Messenger.find(from.server, targetName);
        if (target != null) {
            if (TeleportData.get(from.server).isIgnoring(target.getUUID())) {
                tell(from, "ally.not_taking", target.getDisplayName());
                return false;
            }
            receive(target, request);
            tell(from, "ally.asked", target.getDisplayName(), AllyTeleports.ASK_SECONDS);
            return true;
        }
        if (PlayerDirectory.serverOf(targetName) == null) {
            tell(from, "team.offline", targetName);
            return false;
        }
        boolean sent = Messenger.send(new Message(Message.Type.TP_REQUEST, Message.To.PLAYER, targetName)
                .put("fromName", request.fromName()).put("fromPosition", request.fromPosition().serialize())
                .put("here", "false").put("expiresAt", Long.toString(expiry))
                .put("allyTeam", request.allyTeam()).put("fromId", request.fromId()));
        if (!sent) {
            tell(from, "team.unreachable", targetName, PlayerDirectory.serverOf(targetName));
            return false;
        }
        tell(from, "ally.asked", Component.literal(targetName), AllyTeleports.ASK_SECONDS);
        return true;
    }

    public static void onRemoteRequest(ServerPlayer target, Message m) {
        try {
            receive(target, new Request(m.get("fromName"), m.source, Position.parse(m.get("fromPosition")), m.flag("here"), Long.parseLong(m.get("expiresAt")),
                    m.get("allyTeam"), m.get("fromId")));
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
        if (request.ally()) {
            target.displayClientMessage(Component.translatable("message.bsp_teleport.ally.received", request.fromName(), request.allyTeam(),
                    com.mrgregles.bsp_teleport.teleport.TeleportActions.chatKey("message.bsp_teleport.ally.accept_key", "/tpaccept"),
                    com.mrgregles.bsp_teleport.teleport.TeleportActions.chatKey("message.bsp_teleport.ally.deny_key", "/tpdeny"),
                    AllyTeleports.ASK_SECONDS), false);
        } else {
            tell(target, request.here() ? "request.received_here" : "request.received", Component.literal(request.fromName()));
        }
        com.mrgregles.bsp_teleport.util.Sounds.request(target);
    }

    // Answering

    public static void accept(ServerPlayer target) {
        Request request = take(target);
        if (request == null) {
            return;
        }
        if (request.ally()) {
            acceptAlly(target, request);
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
                AllyTeleports.answered(from.getUUID());
                tell(from, "request.denied", target.getDisplayName());
            }
        } else {
            Messenger.send(new Message(Message.Type.TP_RESPONSE, Message.To.PLAYER, request.fromName())
                    .put("accepted", "false").put("recipientName", target.getGameProfile().getName()));
        }
    }

    /**
     * A-1: the ally accepted. The alliance and the steal rule are checked again on this side first; the asker's server
     * checks them once more before the warm-up and again when it ends ({@link AllyTeleports#start}).
     */
    private static void acceptAlly(ServerPlayer target, Request request) {
        UUID from;
        try {
            from = UUID.fromString(request.fromId());
        } catch (IllegalArgumentException e) {
            return;
        }
        String refusal = AllyTeleports.refusal(target.server, from, target.getUUID());
        if (refusal != null) {
            tell(target, "ally.not_now", request.fromName());
            refuseAsker(target, request, refusal);
            return;
        }
        if (request.local()) {
            ServerPlayer asker = Messenger.find(target.server, request.fromName());
            if (asker == null) {
                tell(target, "request.offline", request.fromName());
                return;
            }
            tell(asker, "request.accepted", target.getDisplayName());
            if (AllyTeleports.start(asker, target.getUUID(), target.getGameProfile().getName(), Position.of(target).toDestination(""))) {
                tell(target, "team.incoming", asker.getDisplayName());
            }
            return;
        }
        boolean sent = Messenger.send(new Message(Message.Type.TP_RESPONSE, Message.To.PLAYER, request.fromName())
                .put("accepted", "true").put("here", "false").put("ally", "true").put("recipientId", target.getUUID().toString())
                .put("recipientName", target.getGameProfile().getName()).put("recipientPosition", Position.of(target).serialize()));
        tell(target, sent ? "ally.accepted_you" : "request.offline", request.fromName());
    }

    /** Tells an ally's asker their ask cannot go ahead ({@code reason} is an {@code ally.*} key taking the ally's name). */
    private static void refuseAsker(ServerPlayer target, Request request, String reason) {
        if (request.local()) {
            ServerPlayer asker = Messenger.find(target.server, request.fromName());
            if (asker != null) {
                AllyTeleports.answered(asker.getUUID());
                tell(asker, reason, target.getDisplayName());
            }
            return;
        }
        Messenger.send(new Message(Message.Type.TP_RESPONSE, Message.To.PLAYER, request.fromName())
                .put("accepted", "false").put("reason", reason).put("recipientName", target.getGameProfile().getName()));
    }

    /**
     * An alliance changed on this server: open ally asks between players no longer allied are dropped, and both are
     * told (the asker through their server when it is another).
     */
    public static void dropAllyRequests(MinecraftServer server) {
        java.util.Iterator<Map.Entry<String, Request>> it = OPEN.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Request> e = it.next();
            Request r = e.getValue();
            ServerPlayer target = Messenger.find(server, e.getKey());
            if (!r.ally() || target == null) {
                continue;
            }
            UUID from;
            try {
                from = UUID.fromString(r.fromId());
            } catch (IllegalArgumentException ex) {
                continue;
            }
            if (!com.mrgregles.bsp_teleport.compat.BspCore.allied(server, from, target.getUUID())) {
                it.remove();
                tell(target, "ally.ask_dropped", r.fromName());
                refuseAsker(target, r, "ally.ended");
            }
        }
    }

    public static void onRemoteResponse(ServerPlayer from, Message m) {
        String recipient = m.get("recipientName");
        if (!m.flag("accepted")) {
            AllyTeleports.answered(from.getUUID());
            String reason = m.get("reason");
            tell(from, reason.startsWith("ally.") ? reason : "request.denied", Component.literal(recipient));
            return;
        }
        tell(from, "request.accepted", Component.literal(recipient));
        if (m.flag("ally")) {
            try {
                AllyTeleports.start(from, UUID.fromString(m.get("recipientId")), recipient, Position.parse(m.get("recipientPosition")).toDestination(""));
            } catch (IllegalArgumentException ignored) {
                // malformed; drop
            }
            return;
        }
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
