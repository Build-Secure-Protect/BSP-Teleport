package com.mrgregles.bsp_teleport.teleport;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.api.BspTeleportApi;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.data.Warp;
import com.mrgregles.bsp_teleport.util.Scheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerEvent;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The network spawn (D140 to D143): one shared warp where new players start, and where first visits and bedless
 * respawns go when the server has no spawn point of its own.
 *
 * <p>A login is left to settle for three seconds (the landing from another server, BSP-InventorySync's restore and
 * the B5 switch gap all happen in that time); a player who has been moved meanwhile is left alone.
 */
public final class NetworkSpawn {
    /** How long a login settles before a new player or a first visit is placed. */
    static final int SETTLE_TICKS = 60;
    /** Players the shared store found no record of when they logged in (D141). */
    private static final Map<UUID, Long> NEW_TO_NETWORK = new ConcurrentHashMap<>();

    private NetworkSpawn() {
    }

    /** The warp that is the network spawn: the one {@code spawn.globalWarp} names, else {@code spawn} (D140). */
    public static String warpName() {
        String global = TeleportConfig.getOr(TeleportConfig.GLOBAL_SPAWN_WARP, "");
        return global.isEmpty() ? "spawn" : global;
    }

    @Nullable
    public static Position position(MinecraftServer server) {
        Warp warp = TeleportData.get(server).warp(warpName());
        return warp == null ? null : warp.position();
    }

    /** D142, D143: this server's own spot for a first visit or a bedless respawn; never another server. */
    @Nullable
    public static Position localSpot(MinecraftServer server) {
        Position own = TeleportData.get(server).spawn();
        if (own != null) {
            return own;
        }
        Position network = position(server);
        return network != null && network.isLocal() ? network : null;
    }

    /** Called by the shared store when it had no record of a player who just logged in. */
    public static void markNewToNetwork(UUID player) {
        NEW_TO_NETWORK.put(player, System.currentTimeMillis());
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // Audit: a mark from an earlier login whose database answer came after its settle must not count for this one.
        NEW_TO_NETWORK.remove(player.getUUID());
        // D142: never left this server before means this is the first visit (stats are kept per server).
        boolean firstVisit = player.getStats().getValue(Stats.CUSTOM.get(Stats.LEAVE_GAME)) == 0;
        Vec3 at = player.position();
        Level level = player.level();
        Scheduler.later(SETTLE_TICKS, () -> settle(player, at, level, firstVisit));
    }

    private static void settle(ServerPlayer player, Vec3 loginAt, Level loginLevel, boolean firstVisit) {
        Long marked = NEW_TO_NETWORK.remove(player.getUUID());
        boolean newToNetwork = marked != null && System.currentTimeMillis() - marked < 120_000L;
        if (player.hasDisconnected() || player.isRemoved()) {
            return;
        }
        if (player.level() != loginLevel || player.position().distanceToSqr(loginAt) > 4.0) {
            return; // a landing, another mod or the player themselves has moved them
        }
        String name = player.getGameProfile().getName();
        if (newToNetwork) {
            Position spawn = position(player.server);
            if (spawn != null) {
                BSPTeleport.LOGGER.info("{} is new to the network: sent to the network spawn ({})", name, warpName());
                BspTeleportApi.get().teleport(player, spawn.toDestination(""));
                return;
            }
        }
        if (firstVisit) {
            Position spot = localSpot(player.server);
            if (spot != null) {
                BSPTeleport.LOGGER.info("{} is new to this server: placed at its spawn", name);
                BspTeleportApi.get().teleport(player, spot.toDestination(""));
            }
        }
    }

    /**
     * D143: a respawn that vanilla put at the world spawn (no bed or anchor, or a broken one, and no Region Guard spawn,
     * which would have put them elsewhere) goes to this server's spawn point or the network spawn here. Runs a tick
     * later so other mods' respawn handling comes first.
     */
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.isEndConquered() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Scheduler.later(1, () -> {
            if (player.hasDisconnected() || player.isRemoved() || !atWorldSpawn(player)) {
                return;
            }
            Position spot = localSpot(player.server);
            if (spot != null) {
                BspTeleportApi.get().teleport(player, spot.toDestination(""));
            }
        });
    }

    /** Whether the player stands where vanilla puts a respawn without a bed: the overworld spawn, within its radius. */
    static boolean atWorldSpawn(ServerPlayer player) {
        ServerLevel overworld = player.server.overworld();
        if (player.level() != overworld) {
            return false;
        }
        BlockPos spawn = overworld.getSharedSpawnPos();
        int radius = Math.max(0, overworld.getGameRules().getInt(GameRules.RULE_SPAWN_RADIUS)) + 2;
        double dx = player.getX() - (spawn.getX() + 0.5), dz = player.getZ() - (spawn.getZ() + 0.5);
        return dx * dx + dz * dz <= (double) radius * radius;
    }
}
