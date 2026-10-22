package com.mrgregles.bsp_teleport.teleport;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.api.Destination;
import com.mrgregles.bsp_teleport.storage.Storage;
import com.mrgregles.bsp_teleport.util.Scheduler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Landings waiting for their player to arrive on this server.
 *
 * <p>With the shared database, the source server wrote a row and this server reads it when the player logs in.
 * Without it, Region Guard's message store carries the announcement, which can arrive before or after the player
 * (it is polled), so both orders are handled: a landing for a player already here is applied at once if they joined
 * recently, and a player who joins is landed if their announcement is already here.
 */
public final class PendingLandings {
    private static final Map<UUID, Entry> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> RECENT_JOINS = new ConcurrentHashMap<>();

    private record Entry(Destination destination, long expiresAt) {
    }

    private PendingLandings() {
    }

    /** Records a landing announced through a message store; applies it now if the player is already here. */
    public static void expect(UUID uuid, Destination destination) {
        long window = TeleportConfig.getOr(TeleportConfig.LANDING_SECONDS, 120) * 1000L;
        PENDING.put(uuid, new Entry(destination, System.currentTimeMillis() + window));
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            Long joined = RECENT_JOINS.get(uuid);
            if (player != null && joined != null && System.currentTimeMillis() - joined < window) {
                landFromMemory(player);
            }
        });
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        RECENT_JOINS.put(player.getUUID(), System.currentTimeMillis());
        com.mrgregles.bsp_teleport.data.TeleportData.get(player.server).rememberName(player.getUUID(), player.getGameProfile().getName());
        // A tick later: the client is not ready for a dimension change inside the login event itself.
        Scheduler.later(1, () -> landFromMemory(player));
        Storage.peekPending(player.getUUID(), destination -> landFromRow(player, destination));
    }

    /**
     * A landing read from the shared table (B7): applied only when it is for this server (any other server the player
     * logs into meanwhile leaves the row alone), and the row is deleted only once the move has happened, so a kick
     * during the switch keeps the landing for the next login until {@code landingSeconds} runs out.
     */
    private static void landFromRow(ServerPlayer player, @Nullable Destination destination) {
        if (destination == null) {
            return;
        }
        String name = player.getGameProfile().getName();
        if (!forThisServer(destination, TeleportConfig.serverName())) {
            BSPTeleport.LOGGER.info("Landing for {} is for {}, not this server; leaving it", name, destination.server());
            return;
        }
        if (player.hasDisconnected()) {
            BSPTeleport.LOGGER.info("{} left before landing; the landing waits for the next login", name);
            return;
        }
        PENDING.remove(player.getUUID());
        Destination to = raidChecked(player, destination);
        if (TeleportService.moveHere(player, to)) {
            Storage.deletePending(player.getUUID());
            BSPTeleport.LOGGER.info("Landed {} at {} {}, {}, {}", name, to.world(), (int) Math.floor(to.x()), (int) Math.floor(to.y()), (int) Math.floor(to.z()));
        }
    }

    /** Whether a stored landing belongs to the server named {@code here}; a landing with no server named is taken as local. */
    public static boolean forThisServer(Destination destination, String here) {
        String server = destination.server();
        return server == null || server.isEmpty() || server.equalsIgnoreCase(here);
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        RECENT_JOINS.remove(event.getEntity().getUUID());
        if (event.getEntity() instanceof ServerPlayer player && !player.isDeadOrDying()) {
            com.mrgregles.bsp_teleport.data.Position where = com.mrgregles.bsp_teleport.data.Position.of(player);
            com.mrgregles.bsp_teleport.data.TeleportData.get(player.server).setOffline(player.getUUID(), where);
            Storage.offlineSet(player.getUUID(), where);
        }
    }

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 100 != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        PENDING.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
        RECENT_JOINS.entrySet().removeIf(e -> now - e.getValue() > 3_600_000L);
    }

    /**
     * The sending server checked the raid rule against its cache; this server sees its own steals live. A landing
     * that would now be near the player's totem being stolen (D39, R6) goes to this server's spawn instead, if it has
     * one, with the same message.
     */
    private static Destination raidChecked(ServerPlayer player, Destination destination) {
        int distance = TeleportConfig.getOr(TeleportConfig.RAID_HOME_DISTANCE, 150);
        if (!com.mrgregles.bsp_teleport.compat.BspCore.nearRaid(player.server, player.getUUID(), "", destination.world(), destination.x(), destination.z(), distance)) {
            return destination;
        }
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.bsp_teleport.blocked_raid", distance), false);
        com.mrgregles.bsp_teleport.data.Position spawn = TeleportActions.spawnPosition(player);
        return spawn != null && spawn.isLocal() ? spawn.toDestination("") : destination;
    }

    private static void landFromMemory(ServerPlayer player) {
        Entry entry = PENDING.remove(player.getUUID());
        if (entry == null || player.hasDisconnected()) {
            return;
        }
        if (entry.expiresAt() < System.currentTimeMillis()) {
            BSPTeleport.LOGGER.info("Landing for {} had expired", player.getGameProfile().getName());
            return;
        }
        TeleportService.moveHere(player, entry.destination());
    }
}
