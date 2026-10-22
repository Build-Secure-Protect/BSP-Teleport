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

    public static void onPlayerLoggedIn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        LOGIN_AT.put(event.getEntity().getUUID(), System.currentTimeMillis());
        // B11: a player who logs in standing in a portal's field (a switch through it failed and the proxy sent them
        // back to where they logged out) must not be sent through again until they step off, or they loop forever.
        if (event.getEntity() instanceof ServerPlayer player
                && com.mrgregles.bsp_teleport.portal.Portals.markLogin(player)) {
            BSPTeleport.LOGGER.info("{} logged in inside a portal's field; it will not fire for them until they step off",
                    player.getGameProfile().getName());
        }
    }

    public static void onPlayerLoggedOut(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        LOGIN_AT.remove(event.getEntity().getUUID());
        WAITING.remove(event.getEntity().getUUID());
    }

    /**
     * B5: a backend switch that starts within {@code network.switchGapSeconds} of the player's login is held until the
     * gap has passed, because the client is still settling from the proxy's Forge reset and a second reset on top of
     * it kicks it ("Can't serialize unregistered packet"). Returns true when the departure was deferred (it runs by
     * itself later). Never defers while the server is stopping.
     */
    private static boolean deferWhileFresh(ServerPlayer player, String server, Runnable departure) {
        int gap = TeleportConfig.getOr(TeleportConfig.SWITCH_GAP_SECONDS, 3);
        Long at = LOGIN_AT.get(player.getUUID());
        if (gap <= 0 || at == null || !player.server.isRunning()) {
            return false;
        }
        long wait = at + gap * 1000L - System.currentTimeMillis();
        if (wait <= 0) {
            return false;
        }
        if (!WAITING.add(player.getUUID())) {
            return true;
        }
        player.displayClientMessage(Component.translatable("message.bsp_teleport.leaving_in", server, (wait + 999) / 1000), true);
        Scheduler.later((int) (wait / 50) + 1, () -> {
            WAITING.remove(player.getUUID());
            if (!player.hasDisconnected() && !player.isRemoved()) {
                departure.run();
            }
        });
        return true;
    }

    @Override
    public boolean teleport(ServerPlayer player, Destination destination) {
        if (player.isDeadOrDying() || player.hasDisconnected()) {
            return false;
        }
        // Every move, forced ones included, is a place /back can return to (an evacuated player comes home with it).
        Teleports.recordBack(player);
        if (!destination.crossServer() || destination.server().equals(serverName())) {
            return moveHere(player, destination);
        }
        if (player.connection == null) {
            return false;
        }
        if (deferWhileFresh(player, destination.server(), () -> teleport(player, destination))) {
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
        if (player.connection == null || server.isEmpty() || player.hasDisconnected()) {
            return false;
        }
        Teleports.recordBack(player);
        if (deferWhileFresh(player, server, () -> sendToServer(player, server))) {
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

    /**
     * The actual departure: off any mount, BSP-Core told (it takes the totems out first, so no snapshot holds one), then
     * BSP-InventorySync told to start the save (T1, when present; its answer does not change anything here), then the
     * proxy asked.
     */
    private static void leave(ServerPlayer player, String server) {
        if (player.hasDisconnected()) {
            return;
        }
        dismount(player);
        BspCore.beginTransfer(player);
        BspInventorySync.beginTransfer(player, server);
        ProxyMessenger.connect(player, server);
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
