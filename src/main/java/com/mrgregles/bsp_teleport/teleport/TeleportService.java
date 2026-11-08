package com.mrgregles.bsp_teleport.teleport;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.api.BspTeleportApi;
import com.mrgregles.bsp_teleport.api.Destination;
import com.mrgregles.bsp_teleport.compat.BspCore;
import com.mrgregles.bsp_teleport.compat.BspInventorySync;
import com.mrgregles.bsp_teleport.compat.RegionGuard;
import com.mrgregles.bsp_teleport.network.Messenger;
import com.mrgregles.bsp_teleport.network.ProxyMessenger;
import com.mrgregles.bsp_teleport.storage.Storage;
import com.mrgregles.bsp_teleport.util.Scheduler;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Forced moves, the API's implementation.
 *
 * <p>A cross-server move writes the landing to the shared database (or, without one, announces it through Region
 * Guard's store), tells BSP-Core the player is transferring, then asks the proxy to move them. The target server lands
 * them on arrival ({@link PendingLandings}).
 */
public final class TeleportService implements BspTeleportApi {
    public static final TeleportService INSTANCE = new TeleportService();

    private TeleportService() {
    }

    @Override
    public String serverName() {
        return TeleportConfig.serverName();
    }

    /** When each online player logged in here, for the switch gap (B5). */
    private static final Map<UUID, Long> LOGIN_AT = new HashMap<>();
    /** Players whose departure is already waiting for the gap to pass. */
    private static final Set<UUID> WAITING = new HashSet<>();
    /** B38: when each player's client was first seen in the world after their login (its first movement packet). */
    private static final Map<UUID, Long> READY_AT = new HashMap<>();
    /** B38: a client that shows no sign of being in the world this long after its login is moved anyway. */
    static final long READY_TIMEOUT_MS = 60_000;
    /** B38: how often a departure waiting for the client looks again. */
    static final long READY_POLL_MS = 500;

    public static void onPlayerLoggedIn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        LOGIN_AT.put(event.getEntity().getUUID(), System.currentTimeMillis());
        // B11: a player who logs in standing in a portal's field (a switch through it failed and the proxy sent them
        // back to where they logged out) must not be sent through again until they step off, or they loop forever.
        if (event.getEntity() instanceof ServerPlayer player
                && com.mrgregles.bsp_teleport.portal.Portals.markLogin(player)) {
            BSPTeleport.LOGGER.info("{} logged in inside a portal's field; it will not fire for them until they step off",
                    player.getGameProfile().getName());
        }
        // B32: and they are moved out in front of it.
        if (event.getEntity() instanceof ServerPlayer player) {
            com.mrgregles.bsp_teleport.portal.Portals.stepOutAfterLogin(player);
        }
    }

    public static void onPlayerLoggedOut(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        LOGIN_AT.remove(event.getEntity().getUUID());
        READY_AT.remove(event.getEntity().getUUID());
        DEPARTING.remove(event.getEntity().getUUID());
        WAITING.remove(event.getEntity().getUUID());
    }

    /**
     * B5: a backend switch that starts within {@code network.switchGapSeconds} of the player's login is held until the
     * gap has passed, because the client is still settling from the proxy's Forge reset and a second reset on top of
     * it kicks it ("Can't serialize unregistered packet"). B38: the gap counts from when the client is in the world,
     * not from the login, which a heavy pack's client can be many seconds behind; and a move the server makes on its
     * own ({@code placing}: the access bounce, which comes at every login of a player without access, and the new
     * player's first placement) waits {@code network.placeGapSeconds}. Before, a player sent back to a server they had
     * no access to was switched about 4 s after the login, while still loading, kicked, and sent back again by the
     * proxy, every time. Returns true when the departure was deferred (it runs by itself later, and checks again).
     * Never defers while the server is stopping.
     */
    private static boolean deferWhileFresh(ServerPlayer player, String server, boolean placing, Runnable departure) {
        int gap = TeleportConfig.getOr(TeleportConfig.SWITCH_GAP_SECONDS, 3);
        if (placing) {
            gap = Math.max(gap, TeleportConfig.getOr(TeleportConfig.PLACE_GAP_SECONDS, 10));
        }
        Long at = LOGIN_AT.get(player.getUUID());
        if (gap <= 0 || at == null || !player.server.isRunning()) {
            return false;
        }
        long wait = switchWait(System.currentTimeMillis(), at, readyAt(player), gap);
        if (wait <= 0) {
            return false;
        }
        if (!WAITING.add(player.getUUID())) {
            return true;
        }
        long shown = READY_AT.containsKey(player.getUUID()) ? wait : gap * 1000L;
        player.displayClientMessage(Component.translatable("message.bsp_teleport.leaving_in", server, (shown + 999) / 1000), true);
        Scheduler.later((int) (wait / 50) + 1, () -> {
            WAITING.remove(player.getUUID());
            if (!player.hasDisconnected() && !player.isRemoved()) {
                departure.run();
            }
        });
        return true;
    }

    /**
     * B38: milliseconds a switch must still wait, 0 to go now. Until the client is in the world ({@code readyAt} null)
     * it waits in short steps, at most {@link #READY_TIMEOUT_MS} after the login; then {@code gapSeconds} from the
     * moment it was first seen there (from the login when it never was).
     */
    static long switchWait(long now, long loginAt, @javax.annotation.Nullable Long readyAt, int gapSeconds) {
        if (readyAt == null && now - loginAt < READY_TIMEOUT_MS) {
            return READY_POLL_MS;
        }
        long from = readyAt != null ? readyAt : loginAt;
        return Math.max(0, from + gapSeconds * 1000L - now);
    }

    /** When the player's client was first seen in the world since their login, or null while it has not been. */
    @javax.annotation.Nullable
    private static Long readyAt(ServerPlayer player) {
        return READY_AT.get(player.getUUID());
    }

    /** B38: records the first movement packet of each fresh login, checked every tick while any is outstanding. */
    private static void watchReady() {
        if (LOGIN_AT.size() == READY_AT.size()) {
            return;
        }
        net.minecraft.server.MinecraftServer mc = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (mc == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (UUID id : LOGIN_AT.keySet()) {
            if (READY_AT.containsKey(id)) {
                continue;
            }
            ServerPlayer p = mc.getPlayerList().getPlayer(id);
            if (p != null && p.connection instanceof com.mrgregles.bsp_teleport.mixin.ServerGamePacketListenerAccessor c
                    && c.bsp_teleport$receivedMovePacketCount() > 0) {
                READY_AT.put(id, now);
            }
        }
    }

    /** Players being moved by {@link #place}: their move leaves /back as it is. */
    private static final Set<UUID> PLACING = new HashSet<>();

    /**
     * B31: a move the server makes on its own, not one the player or an admin asked for: the respawn fix (D143), the
     * first placement on a server or the network (D141), an access bounce. /back is left alone, so it still holds the
     * death spot or the last real departure. Before, the respawn fix saved the spot vanilla had respawned the player at
     * (in the air above the world spawn), and the band's Back took them there.
     */
    public boolean place(ServerPlayer player, Destination destination) {
        PLACING.add(player.getUUID());
        try {
            return teleport(player, destination);
        } finally {
            PLACING.remove(player.getUUID());
        }
    }

    /** {@link #sendToServer} as a {@link #place}. */
    public boolean placeOnServer(ServerPlayer player, String server) {
        PLACING.add(player.getUUID());
        try {
            return sendToServer(player, server);
        } finally {
            PLACING.remove(player.getUUID());
        }
    }

    @Override
    public boolean teleport(ServerPlayer player, Destination destination) {
        if (player.isDeadOrDying() || player.hasDisconnected()) {
            return false;
        }
        // Every move, forced ones included, is a place /back can return to (an evacuated player comes home with it),
        // except one the server makes on its own (B31).
        boolean placing = PLACING.contains(player.getUUID());
        if (!placing) {
            Teleports.recordBack(player);
        }
        if (!leavesServer(destination)) {
            return moveHere(player, destination);
        }
        if (player.connection == null) {
            return false;
        }
        if (deferWhileFresh(player, destination.server(), placing, () -> {
            if (placing) {
                place(player, destination);
            } else {
                teleport(player, destination);
            }
        })) {
            return true;
        }
        UUID uuid = player.getUUID();
        if (!Storage.enabled() && !RegionGuard.announceLanding(uuid, destination)) {
            BSPTeleport.LOGGER.warn("No shared store: {} is sent to '{}' but will land wherever that server puts them",
                    player.getGameProfile().getName(), destination.server());
        }
        if (!destination.message().isEmpty()) {
            player.displayClientMessage(Component.literal(destination.message()), false);
        }
        Storage.savePending(uuid, destination, () -> leave(player, destination.server()));
        return true;
    }

    @Override
    public boolean sendToServer(ServerPlayer player, String server) {
        if (player.connection == null || server.isEmpty() || player.hasDisconnected() || TeleportConfig.singlePlayer()) {
            return false;
        }
        boolean placing = PLACING.contains(player.getUUID());
        if (!placing) {
            Teleports.recordBack(player);
        }
        if (deferWhileFresh(player, server, placing, () -> {
            if (placing) {
                placeOnServer(player, server);
            } else {
                sendToServer(player, server);
            }
        })) {
            return true;
        }
        leave(player, server);
        return true;
    }

    @Override
    public int sendAllToServer(String server) {
        MinecraftServer mc = ServerLifecycleHooks.getCurrentServer();
        if (mc == null) {
            return 0;
        }
        int sent = 0;
        for (ServerPlayer player : new ArrayList<>(mc.getPlayerList().getPlayers())) {
            if (sendToServer(player, server)) {
                sent++;
            }
        }
        return sent;
    }

    @Override
    public boolean publish(String channel, String payload) {
        return Messenger.publishCustom(channel, payload);
    }

    @Override
    public void subscribe(String channel, BiConsumer<String, String> handler) {
        Messenger.subscribeCustom(channel, handler);
    }

    @Override
    public boolean messagingAvailable() {
        return Messenger.enabled();
    }

    @Override
    public List<com.mrgregles.bsp_teleport.api.WarpInfo> warps() {
        return com.mrgregles.bsp_teleport.data.ApiSnapshot.warps();
    }

    @Override
    public java.util.Optional<Destination> spawn(String server) {
        return com.mrgregles.bsp_teleport.data.ApiSnapshot.spawn(server);
    }

    /**
     * The actual departure: off any mount, BSP-Core told (it takes the totems out first, so no snapshot holds one), then
     * BSP-InventorySync told to start the save (T1, when present; its answer does not change anything here), then the
     * proxy asked.
     */
    private static void leave(ServerPlayer player, String server) {
        if (player.hasDisconnected()) {
            return;
        }
        // B21: a client still walking when the proxy switch starts can be kicked ("Can't serialize unregistered packet");
        // hold the player still, movement keys off, for departureHoldTicks first. Not while the server is stopping.
        int hold = TeleportConfig.getOr(TeleportConfig.DEPARTURE_HOLD_TICKS, 20);
        if (hold > 0 && player.server.isRunning() && !DEPARTING.containsKey(player.getUUID())) {
            DEPARTING.put(player.getUUID(), new Departure(player, server, player.position(), hold));
            player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            com.mrgregles.bsp_teleport.network.BandPackets.sendDeparting(player, hold + 40);
            return;
        }
        if (DEPARTING.containsKey(player.getUUID())) {
            return; // already on the way out
        }
        go(player, server);
    }

    private record Departure(ServerPlayer player, String server, net.minecraft.world.phys.Vec3 at, int ticks) {
    }

    private static final Map<UUID, Departure> DEPARTING = new java.util.concurrent.ConcurrentHashMap<>();

    /** Whether the player is being held before a switch (B21). */
    public static boolean isDeparting(ServerPlayer player) {
        return DEPARTING.containsKey(player.getUUID());
    }

    /** Ends every hold now and sends those players off (the GameTests; never needed in play). */
    public static void finishDepartures() {
        for (Map.Entry<UUID, Departure> e : DEPARTING.entrySet()) {
            DEPARTING.remove(e.getKey());
            go(e.getValue().player(), e.getValue().server());
        }
    }

    /** B21: every tick, keep each departing player in place and send them off once their hold is over. */
    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        watchReady();
        if (DEPARTING.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Departure> e : DEPARTING.entrySet()) {
            Departure d = e.getValue();
            ServerPlayer player = d.player();
            if (player.hasDisconnected() || player.isRemoved() || player.isDeadOrDying()) {
                DEPARTING.remove(e.getKey());
                continue;
            }
            if (player.position().distanceToSqr(d.at()) > 1.0E-4) {
                player.connection.teleport(d.at().x, d.at().y, d.at().z, player.getYRot(), player.getXRot());
                player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                player.fallDistance = 0f;
            }
            if (d.ticks() <= 1) {
                DEPARTING.remove(e.getKey());
                go(player, d.server());
            } else {
                DEPARTING.put(e.getKey(), new Departure(player, d.server(), d.at(), d.ticks() - 1));
            }
        }
    }

    /** The switch itself: off any mount, BSP-Core and BSP-InventorySync told, then the proxy asked. */
    private static void go(ServerPlayer player, String server) {
        if (player.hasDisconnected()) {
            return;
        }
        dismount(player);
        BspCore.beginTransfer(player);
        BspInventorySync.beginTransfer(player, server);
        if (ProxyMessenger.connect(player, server)) {
            PaidTrips.asked(player); // B34: from here, leaving this server is the move
        }
    }

    /** Whether a move to {@code destination} goes through the proxy to another server, rather than a move on this one. */
    public static boolean leavesServer(Destination destination) {
        // T89 audit: this server's name in any case is a local move, as for landings (PendingLandings.forThisServer);
        // before, "bsp-spawn" on BSP-Spawn asked the proxy to move the player to the server they were on.
        return destination.crossServer() && !destination.server().equalsIgnoreCase(TeleportConfig.serverName()) && !TeleportConfig.singlePlayer();
    }

    private static void dismount(ServerPlayer player) {
        player.stopRiding();
        for (Entity passenger : new ArrayList<>(player.getPassengers())) {
            passenger.stopRiding();
        }
    }

    /** Moves a player on this server, changing dimension if needed. */
    static boolean moveHere(ServerPlayer player, Destination destination) {
        ServerLevel level = levelOf(player.server, destination.world());
        if (level == null) {
            BSPTeleport.LOGGER.warn("Cannot land {}: world '{}' does not exist here", player.getGameProfile().getName(), destination.world());
            return false;
        }
        float yaw = destination.hasFacing() ? destination.yaw() : player.getYRot();
        float pitch = destination.hasFacing() ? destination.pitch() : player.getXRot();
        dismount(player);
        player.fallDistance = 0f;
        player.teleportTo(level, destination.x(), destination.y(), destination.z(), yaw, pitch);
        player.fallDistance = 0f;
        // D112 (B9): a landing inside a portal's field does not fire it until the player walks off.
        com.mrgregles.bsp_teleport.portal.Portals.markIfInField(player);
        protect(player, destination.protect());
        com.mrgregles.bsp_teleport.util.Sounds.arrived(player);
        if (!destination.message().isEmpty()) {
            player.displayClientMessage(Component.literal(destination.message()), false);
        }
        return true;
    }

    /** Arrival protection: the destination's own seconds, or the config value when it says -1. */
    static void protect(ServerPlayer player, int requested) {
        int seconds = requested >= 0 ? requested : TeleportConfig.getOr(TeleportConfig.INVULNERABLE_SECONDS, 0);
        if (seconds <= 0 || player.isInvulnerable()) {
            return;
        }
        player.setInvulnerable(true);
        Scheduler.later(seconds * 20, () -> {
            if (!player.isRemoved()) {
                player.setInvulnerable(false);
            }
        });
    }

    /** The level for a dimension id on this server, or null. */
    @Nullable
    public static ServerLevel levelOf(MinecraftServer server, String world) {
        ResourceLocation id = ResourceLocation.tryParse(world);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    /** The dimension ids this server has, for messages and checks. */
    public static List<String> worldIds(MinecraftServer server) {
        List<String> ids = new ArrayList<>();
        for (ResourceKey<Level> key : server.levelKeys()) {
            ids.add(key.location().toString());
        }
        return ids;
    }
}
