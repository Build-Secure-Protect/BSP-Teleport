package com.mrgregles.bsp_teleport.teleport;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.api.Destination;
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
 * <p>A cross-server teleport is two halves: the source server announces the landing and sends the player through
 * the proxy; this server lands them when they log in. The announcement and the player can arrive in either order
 * (the message store is polled), so both sides check: a landing for a player who is already here is applied at
 * once if they joined recently, and a player who joins is landed if their announcement is already here.
 */
public final class PendingLandings {
    private static final Map<UUID, Entry> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> RECENT_JOINS = new ConcurrentHashMap<>();

    private record Entry(Destination destination, long expiresAt) {
    }

    private PendingLandings() {
    }

    /** Records a landing for a player expected to arrive; applies it now if they are already here. */
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
                land(player);
            }
        });
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        RECENT_JOINS.put(player.getUUID(), System.currentTimeMillis());
        // One tick later: the client is not ready for a dimension change inside the login event itself.
        player.server.execute(() -> land(player));
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        RECENT_JOINS.remove(event.getEntity().getUUID());
    }

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 100 != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        PENDING.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
        RECENT_JOINS.entrySet().removeIf(e -> now - e.getValue() > 3_600_000L);
    }

    private static void land(ServerPlayer player) {
        Entry entry = PENDING.remove(player.getUUID());
        if (entry == null) {
            return;
        }
        if (entry.expiresAt() < System.currentTimeMillis()) {
            BSPTeleport.LOGGER.info("Landing for {} had expired", player.getGameProfile().getName());
            return;
        }
        TeleportService.moveHere(player, entry.destination());
    }
}
