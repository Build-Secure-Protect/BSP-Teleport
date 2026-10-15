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
        Storage.takePending(player.getUUID(), destination -> {
            if (destination != null && !player.hasDisconnected()) {
                PENDING.remove(player.getUUID());
                TeleportService.moveHere(player, destination);
            }
        });
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
