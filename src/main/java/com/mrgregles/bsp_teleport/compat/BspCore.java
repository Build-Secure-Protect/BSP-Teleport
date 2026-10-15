package com.mrgregles.bsp_teleport.compat;

import com.mrgregles.bsp_core.BSPConfig;
import com.mrgregles.bsp_core.api.BspPlayers;
import com.mrgregles.bsp_core.api.BspTotems;
import com.mrgregles.bsp_core.api.BspTransfer;
import com.mrgregles.bsp_core.api.BspZones;
import com.mrgregles.bsp_core.api.event.RecallEvent;
import com.mrgregles.bsp_core.api.event.SeasonEvent;
import com.mrgregles.bsp_core.api.event.TotemEvent;
import com.mrgregles.bsp_core.totem.TotemUpgrades;
import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.api.BspTeleportApi;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.network.Messenger;
import com.mrgregles.bsp_teleport.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The only class that touches BSP-Core, through its {@code api} package (BSP-Core 1.0.1 with requests R1 to R5 built).
 *
 * <p>Besides the lookups, this is where BSP-Core's events are answered: a full season reset wipes homes (D13), a
 * successful steal starts the thief's no-teleport window (D18), and a cross-server Recall is carried out (R3).
 */
public final class BspCore {
    /** Thieves who just took a totem, and when their invincibility (and teleport block) ends. */
    private static final Map<UUID, Long> THIEF_UNTIL = new ConcurrentHashMap<>();

    private BspCore() {
    }

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener(BspCore::onSeasonEnded);
        MinecraftForge.EVENT_BUS.addListener(BspCore::onStealEnded);
        MinecraftForge.EVENT_BUS.addListener(BspCore::onRecall);
    }

    // Players

    public static boolean isAdmin(Player player) {
        return BspPlayers.isAdmin(player);
    }

    public static boolean isModerator(Player player) {
        return BspPlayers.isModerator(player);
    }

    /** Total Shatter Coin value the player carries, in Copper. */
    public static int coins(ServerPlayer player) {
        return BspPlayers.coinValue(player);
    }

    /** Takes coins worth {@code value} from the player; false when they cannot pay. */
    public static boolean pay(ServerPlayer player, int value) {
        return value <= 0 || BspPlayers.payCoins(player, value);
    }

    // Totem upgrades (R3): one home slot per Extra Homes level, 25 % off the cooldown per Swift Travel level

    public static int extraHomeSlots(ServerPlayer player) {
        return BspTotems.highestLevel(player, TotemUpgrades.Buff.EXTRA_HOMES);
    }

    public static int swiftTravelLevel(ServerPlayer player) {
        return Math.min(3, BspTotems.highestLevel(player, TotemUpgrades.Buff.SWIFT_TRAVEL));
    }

    public static int cooldownSeconds(ServerPlayer player, int base) {
        int level = Math.min(3, BspTotems.highestLevel(player, TotemUpgrades.Buff.SWIFT_TRAVEL));
        return level <= 0 ? base : (int) Math.ceil(base * (1.0 - 0.25 * level));
    }

    // Anti Totem zones (R4)

    public static boolean canTeleportFrom(ServerLevel level, BlockPos pos, @Nullable ServerPlayer player) {
        return !BspZones.isBlocked(level, pos, BspZones.TELEPORT_OUT);
    }

    public static boolean canTeleportTo(ServerLevel level, BlockPos pos, @Nullable ServerPlayer player) {
        return !BspZones.isBlocked(level, pos, BspZones.TELEPORT_IN);
    }

    public static boolean canSetHome(ServerLevel level, BlockPos pos, @Nullable ServerPlayer player) {
        return !BspZones.isBlocked(level, pos, BspZones.SET_HOME);
    }

    // Raids (D18)

    /** Whether one of the player's totems, on any server of the network, is being stolen right now (R6). */
    public static boolean beingRaided(ServerPlayer player) {
        return beingRaided(player.server, player.getUUID());
    }

    public static boolean beingRaided(net.minecraft.server.MinecraftServer server, UUID owner) {
        return !BspTotems.stealsAnywhere(server, owner).isEmpty();
    }

    /**
     * Whether a spot on {@code targetServer} (the proxy name, or empty for this server) in {@code dimensionId} is
     * within {@code distance} blocks, horizontally, of one of {@code owner}'s totems being stolen right now, on any
     * server (R6). A remote steal may lag the cache by {@code storage.noticeSeconds} without Redis.
     */
    public static boolean nearRaid(net.minecraft.server.MinecraftServer server, UUID owner, String targetServer, String dimensionId, double x, double z, int distance) {
        String target = targetServer.isEmpty() ? TeleportConfig.serverName() : targetServer;
        for (BspTotems.StealAnywhere steal : BspTotems.stealsAnywhere(server, owner)) {
            if (!proxyNameFor(steal.serverId()).equals(target) || !steal.dimension().location().toString().equals(dimensionId)) {
                continue;
            }
            BlockPos t = steal.pos();
            double dx = t.getX() + 0.5 - x, dz = t.getZ() + 0.5 - z;
            if (dx * dx + dz * dz < (double) distance * distance) {
                return true;
            }
        }
        return false;
    }

    /** The same check for a spot on this server. */
    public static boolean nearRaid(ServerPlayer player, ServerLevel level, double x, double z, int distance) {
        return nearRaid(player.server, player.getUUID(), "", level.dimension().location().toString(), x, z, distance);
    }

    /** Seconds left of the no-teleport window after the player stole a totem; 0 when none. */
    public static long thiefBlockLeft(ServerPlayer player) {
        Long until = THIEF_UNTIL.get(player.getUUID());
        if (until == null) {
            return 0;
        }
        long left = until - System.currentTimeMillis();
        if (left <= 0) {
            THIEF_UNTIL.remove(player.getUUID());
            return 0;
        }
        return (left + 999) / 1000;
    }

    private static void onStealEnded(TotemEvent.StealEnded event) {
        if (!event.isSuccess()) {
            return;
        }
        int seconds = BSPConfig.getOr(BSPConfig.STEAL_INVINCIBILITY_SECONDS, 0);
        if (seconds > 0) {
            THIEF_UNTIL.put(event.getThief(), System.currentTimeMillis() + seconds * 1000L);
        }
    }

    // Transfers (R5)

    /** Just before a real server transfer: BSP-Core takes the carried totems along instead of placing them on logout. */
    public static void beginTransfer(ServerPlayer player) {
        BspTransfer.beginTransfer(player);
    }

    public static void endTransfer(ServerPlayer player) {
        BspTransfer.endTransfer(player);
    }

    // Seasons (D13)

    private static void onSeasonEnded(SeasonEvent.Ended event) {
        if (!event.isFullReset()) {
            return;
        }
        TeleportData.get(event.getServer()).clearHomes();
        Storage.wipeHomes();
        Messenger.homesWiped();
        BSPTeleport.LOGGER.info("Season {} ended with a full reset: every home wiped", event.getEndedNumber());
    }

    // Cross-server Recall (R3)

    private static void onRecall(RecallEvent.CrossServer event) {
        if (!(event.getOwner() instanceof ServerPlayer owner)) {
            return;
        }
        String target = proxyNameFor(event.getTargetServer());
        if (BspTeleportApi.get().sendToServer(owner, target)) {
            event.setHandled();
        }
    }

    /**
     * BSP-Core names servers by its {@code storage.serverId}; the proxy by {@code network.serverName}. Config
     * {@code network.serverAliases} maps one to the other as {@code bspCoreId=proxyName}; unmapped ids are used as they are.
     */
    static String proxyNameFor(String bspCoreServerId) {
        for (String alias : TeleportConfig.getOr(TeleportConfig.SERVER_ALIASES, List.of())) {
            int eq = alias.indexOf('=');
            if (eq > 0 && alias.substring(0, eq).trim().equals(bspCoreServerId)) {
                return alias.substring(eq + 1).trim();
            }
        }
        return bspCoreServerId;
    }

    public static Optional<String> describeSteal(ServerPlayer player) {
        return BspTotems.stealsAnywhere(player.server, player.getUUID()).stream().findFirst()
                .map(s -> s.thiefName() + " at " + s.pos().toShortString() + " on " + proxyNameFor(s.serverId()));
    }
}
