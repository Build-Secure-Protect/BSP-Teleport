package com.mrgregles.bsp_teleport.network;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.api.BspTeleportApi;
import com.mrgregles.bsp_teleport.api.Destination;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.request.TeleportRequests;
import com.mrgregles.bsp_teleport.portal.Portals;
import com.mrgregles.bsp_teleport.storage.Storage;
import com.mrgregles.bsp_teleport.teleport.RandomTeleport;
import com.mrgregles.bsp_teleport.teleport.TeleportKind;
import com.mrgregles.bsp_teleport.teleport.TeleportService;
import com.mrgregles.bsp_teleport.teleport.Teleports;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Live messages between servers over Redis.
 *
 * <p>Every message is handled on the server thread. Messages from this server are ignored. Without Redis, every
 * send is a no-op and the network features that need it (cross-server requests and random teleports, the player
 * list) stay quiet; cross-server teleports themselves only need the database.
 */
public final class Messenger {
    @Nullable
    private static RedisLink link;
    private static final Map<String, BiConsumer<String, String>> CUSTOM = new ConcurrentHashMap<>();

    private Messenger() {
    }

    public static boolean enabled() {
        return link != null;
    }

    public static String status() {
        RedisLink l = link;
        return l == null ? (TeleportConfig.redis() ? "redis: not started" : "redis: off") : "redis: " + l.describe();
    }

    // Lifecycle

    public static void onServerStarted(ServerStartedEvent event) {
        com.mrgregles.bsp_teleport.compat.RegionGuard.checkServerName();
        if (!TeleportConfig.redis()) {
            return;
        }
        String channel = "bsp_teleport:" + TeleportConfig.REDIS_CLUSTER.get() + ":v" + BspTeleportApi.API_VERSION;
        RedisLink l = new RedisLink(TeleportConfig.REDIS_HOST.get(), TeleportConfig.REDIS_PORT.get(), TeleportConfig.REDIS_PASSWORD.get(),
                TeleportConfig.REDIS_SSL.get(), TeleportConfig.REDIS_DB.get(), channel, Messenger::onRaw);
        link = l;
        l.start();
        BSPTeleport.LOGGER.info("Redis messaging on: {}", l.describe());
        send(new Message(Message.Type.PLAYER_LIST_REQUEST, Message.To.ALL, Message.ALL));
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        RedisLink l = link;
        if (l == null) {
            return;
        }
        // Tell the others this server is going, then stop.
        send(new Message(Message.Type.PLAYER_LIST, Message.To.ALL, Message.ALL).put("names", "").put("down", "true"));
        link = null;
        l.close();
        PlayerDirectory.clear();
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            publishPlayerList(player.server, null);
        }
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            publishPlayerList(player.server, player);
        }
    }

    /** Heartbeat every 30 s so an empty server still counts as up, and stale servers drop out. */
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || link == null || event.getServer().getTickCount() % 600 != 0) {
            return;
        }
        publishPlayerList(event.getServer(), null);
        PlayerDirectory.prune();
    }

    // Sending

    public static boolean send(Message message) {
        RedisLink l = link;
        if (l == null) {
            return false;
        }
        message.source = TeleportConfig.serverName();
        return l.publish(message.toJson());
    }

    private static void publishPlayerList(MinecraftServer server, @Nullable ServerPlayer leaving) {
        if (link == null) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p != leaving) {
                names.add(p.getGameProfile().getName());
            }
        }
        send(new Message(Message.Type.PLAYER_LIST, Message.To.ALL, Message.ALL).put("names", String.join(",", names)));
    }

    public static void homesChanged(UUID owner) {
        send(new Message(Message.Type.HOMES_CHANGED, Message.To.ALL, Message.ALL).put("uuid", owner.toString()));
    }

    public static void homesWiped() {
        send(new Message(Message.Type.HOMES_WIPED, Message.To.ALL, Message.ALL));
    }

    public static void warpsChanged() {
        send(new Message(Message.Type.WARPS_CHANGED, Message.To.ALL, Message.ALL));
    }

    /** Asks the server a portal stands on to open, close, lock or unlock it. */
    public static boolean portalCommand(String server, String rowId, String command) {
        return send(new Message(Message.Type.PORTAL_DO, Message.To.SERVER, server).put("id", rowId).put("command", command));
    }

    public static void portalsChanged() {
        send(new Message(Message.Type.PORTALS_CHANGED, Message.To.ALL, Message.ALL));
    }

    /** A chat line to a player wherever they are on the network. */
    public static boolean tell(String playerName, String text) {
        return send(new Message(Message.Type.TELL, Message.To.PLAYER, playerName).put("text", text));
    }

    // Custom channels for other mods (API)

    public static boolean publishCustom(String channel, String payload) {
        return send(new Message(Message.Type.CUSTOM, Message.To.ALL, Message.ALL).put("channel", channel).put("payload", payload));
    }

    public static void subscribeCustom(String channel, BiConsumer<String, String> handler) {
        CUSTOM.put(channel, handler);
    }

    // Receiving

    private static void onRaw(String json) {
        Message message;
        try {
            message = Message.fromJson(json);
        } catch (RuntimeException e) {
            BSPTeleport.LOGGER.warn("Bad message from Redis: {}", e.getMessage());
            return;
        }
        if (message.source.equals(TeleportConfig.serverName())) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            server.execute(() -> handle(server, message));
        }
    }

    private static void handle(MinecraftServer server, Message m) {
        if (m.to == Message.To.SERVER && !m.target.equals(TeleportConfig.serverName())) {
            return;
        }
        ServerPlayer player = m.to == Message.To.PLAYER ? find(server, m.target) : null;
        if (m.to == Message.To.PLAYER && player == null) {
            return;
        }
        switch (m.type) {
            case PLAYER_LIST -> {
                if (m.flag("down")) {
                    PlayerDirectory.markDown(m.source);
                } else {
                    String names = m.get("names");
                    PlayerDirectory.update(m.source, names.isEmpty() ? List.of() : List.of(names.split(",")));
                }
            }
            case PLAYER_LIST_REQUEST -> publishPlayerList(server, null);
            case HOMES_CHANGED -> {
                ServerPlayer owner = server.getPlayerList().getPlayer(UUID.fromString(m.get("uuid")));
                if (owner != null) {
                    Storage.reloadHomes(owner);
                }
            }
            case HOMES_WIPED -> com.mrgregles.bsp_teleport.data.TeleportData.get(server).clearHomes();
            case WARPS_CHANGED -> Storage.reloadWarps(server);
            case PORTALS_CHANGED -> Storage.reloadPortals(server);
            case PORTAL_DO -> Portals.applyCommand(server, m.get("id"), m.get("command"));
            case TP_REQUEST -> {
                if (m.to == Message.To.ALL) {
                    for (ServerPlayer p : new ArrayList<>(server.getPlayerList().getPlayers())) {
                        TeleportRequests.onRemoteRequest(p, m);
                    }
                } else {
                    TeleportRequests.onRemoteRequest(player, m);
                }
            }
            case TP_FORCE -> {
                Destination d = Destination.parse(m.get("destination"));
                TeleportService.INSTANCE.teleport(player, d);
                player.displayClientMessage(Component.translatable("message.bsp_teleport." + (m.get("reason").isEmpty() ? "admin.moved" : m.get("reason")), m.get("by")), false);
            }
            case TP_ALL -> {
                Destination d = Destination.parse(m.get("destination"));
                String exclude = m.get("exclude");
                for (ServerPlayer p : new ArrayList<>(server.getPlayerList().getPlayers())) {
                    if (!p.getGameProfile().getName().equalsIgnoreCase(exclude)) {
                        TeleportService.INSTANCE.teleport(p, d);
                        p.displayClientMessage(Component.translatable("message.bsp_teleport.admin.moved", m.get("by")), false);
                    }
                }
            }
            case SEND_ALL -> {
                for (ServerPlayer p : new ArrayList<>(server.getPlayerList().getPlayers())) {
                    if (TeleportService.INSTANCE.sendToServer(p, m.get("to"))) {
                        p.displayClientMessage(Component.translatable("message.bsp_teleport.admin.moved", m.get("by")), false);
                    }
                }
            }
            case POSITION_REQUEST -> send(new Message(Message.Type.POSITION_RESULT, Message.To.PLAYER, m.get("asker"))
                    .put("player", player.getGameProfile().getName()).put("position", Position.of(player).serialize()).put("purpose", m.get("purpose")));
            case POSITION_RESULT -> {
                Position where = Position.parse(m.get("position"));
                if ("here".equals(m.get("purpose"))) {
                    // The asker wanted the other player brought to them; the position answer is not needed for that.
                    return;
                }
                TeleportService.INSTANCE.teleport(player, where.toDestination(""));
                player.displayClientMessage(Component.translatable("message.bsp_teleport.teleported", Component.literal(m.get("player"))), false);
            }
            case TP_RESPONSE -> TeleportRequests.onRemoteResponse(player, m);
            case RTP_REQUEST -> onRtpRequest(server, m);
            case RTP_RESULT -> onRtpResult(player, m);
            case TELL -> player.displayClientMessage(Component.literal(m.get("text")), false);
            case CUSTOM -> {
                BiConsumer<String, String> handler = CUSTOM.get(m.get("channel"));
                if (handler != null) {
                    handler.accept(m.source, m.get("payload"));
                }
            }
        }
    }

    @Nullable
    public static ServerPlayer find(MinecraftServer server, String name) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getGameProfile().getName().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    // Random teleports on another server

    /** Asks {@code targetServer} for a random spot; the answer comes back as RTP_RESULT for the player. */
    public static boolean requestRandomSpot(ServerPlayer player, String targetServer) {
        return requestRandomSpot(player, targetServer, "");
    }

    /** As above; a non-empty {@code portal} (the core's row id) makes the answer go through the portal rules instead of the warm-up. */
    public static boolean requestRandomSpot(ServerPlayer player, String targetServer, String portal) {
        return send(new Message(Message.Type.RTP_REQUEST, Message.To.SERVER, targetServer).put("player", player.getGameProfile().getName())
                .put("uuid", player.getUUID().toString()).put("portal", portal));
    }

    private static void onRtpRequest(MinecraftServer server, Message m) {
        Message reply = new Message(Message.Type.RTP_RESULT, Message.To.PLAYER, m.get("player")).put("portal", m.get("portal"));
        ServerLevel level = RandomTeleport.arrivalWorld(server);
        UUID owner = null;
        try {
            owner = m.get("uuid").isEmpty() ? null : UUID.fromString(m.get("uuid"));
        } catch (IllegalArgumentException ignored) {
            // an old sender without the uuid: no raid avoidance for them
        }
        Position spot = RandomTeleport.enabled() && level != null ? RandomTeleport.find(level, null, owner) : null;
        if (spot == null) {
            reply.put("error", RandomTeleport.enabled() ? "rtp.no_spot" : "rtp.disabled_there");
        } else {
            reply.put("position", spot.serialize());
        }
        send(reply);
    }

    private static void onRtpResult(ServerPlayer player, Message m) {
        if (!m.get("error").isEmpty()) {
            player.displayClientMessage(Component.translatable("message.bsp_teleport." + m.get("error"), m.source), false);
            return;
        }
        Position spot = Position.parse(m.get("position"));
        if (!m.get("portal").isEmpty()) {
            Portals.remoteRandomSpot(player, m.get("portal"), spot);
            return;
        }
        Teleports.start(player, spot.toDestination(""), Component.translatable("message.bsp_teleport.rtp.name_on", m.source), TeleportKind.RANDOM, null);
    }

    // Admin tools across servers

    /** Moves a player on another server, forced, to a destination. */
    public static boolean forceMove(String playerName, Destination destination, String byName, String reasonKey) {
        return send(new Message(Message.Type.TP_FORCE, Message.To.PLAYER, playerName)
                .put("destination", destination.serialize()).put("by", byName).put("reason", reasonKey));
    }

    /** Moves everyone on {@code server} (or every server when null), except {@code exclude}, to a destination. */
    public static boolean moveAll(@Nullable String server, Destination destination, String byName, String exclude) {
        Message m = server == null ? new Message(Message.Type.TP_ALL, Message.To.ALL, Message.ALL) : new Message(Message.Type.TP_ALL, Message.To.SERVER, server);
        return send(m.put("destination", destination.serialize()).put("by", byName).put("exclude", exclude));
    }

    /** Everyone on {@code from} goes to {@code to}. */
    public static boolean sendAllFrom(String from, String to, String byName) {
        return send(new Message(Message.Type.SEND_ALL, Message.To.SERVER, from).put("to", to).put("by", byName));
    }

    /** Asks where a player on another server is; the answer moves the asker there. */
    public static boolean askPosition(ServerPlayer asker, String playerName) {
        return send(new Message(Message.Type.POSITION_REQUEST, Message.To.PLAYER, playerName)
                .put("asker", asker.getGameProfile().getName()).put("purpose", "to"));
    }
}
