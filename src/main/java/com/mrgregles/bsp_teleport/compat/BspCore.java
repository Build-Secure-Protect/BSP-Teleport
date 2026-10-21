package com.mrgregles.bsp_teleport.compat;

import com.mrgregles.bsp_core.BSPConfig;
import com.mrgregles.bsp_core.api.BspBoards;
import com.mrgregles.bsp_core.api.BspPlayers;
import com.mrgregles.bsp_core.api.BspTeams;
import com.mrgregles.bsp_core.api.BspTotems;
import com.mrgregles.bsp_core.api.BspTransfer;
import com.mrgregles.bsp_core.api.BspZones;
import com.mrgregles.bsp_core.api.event.RecallEvent;
import com.mrgregles.bsp_core.api.event.SeasonEvent;
import com.mrgregles.bsp_core.api.event.TeamEvent;
import com.mrgregles.bsp_core.api.event.TotemEvent;
import com.mrgregles.bsp_core.totem.TotemUpgrades;
import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.api.BspTeleportApi;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.network.Messenger;
import com.mrgregles.bsp_teleport.storage.Storage;
import com.mrgregles.bsp_teleport.team.TeamTeleports;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The only class that touches BSP-Core, through its {@code api} package (BSP-Core 1.3.0 with requests R1 to R8 built).
 *
 * <p>Besides the lookups, this is where BSP-Core's events are answered: a full season reset wipes homes (D13), a
 * successful steal starts the thief's no-teleport window (D18), a cross-server Recall is carried out (R3), and a
 * team teleport asked for on BSP-Core's Team screen is run (T-1, D91). Team lookups for the band's TEAM tab are here
 * too, with BSP-Core's server ids turned into proxy names.
 */
public final class BspCore {
    /**
     * The MySQL driver inside BSP-Core's full jar, which relocates MySQL Connector/J so it never clashes with Mohist's
     * copy (RG-C4). On plain Forge it is the only driver there is (B8). Not part of BSP-Core's api: R9 asks for one.
     */
    public static final String MYSQL_DRIVER = "com.mrgregles.bsp_core.shaded.mysql.cj.jdbc.Driver";

    /** Thieves who just took a totem, and when their invincibility (and teleport block) ends. */
    private static final Map<UUID, Long> THIEF_UNTIL = new ConcurrentHashMap<>();

    private BspCore() {
    }

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener(BspCore::onSeasonEnded);
        MinecraftForge.EVENT_BUS.addListener(BspCore::onStealEnded);
        MinecraftForge.EVENT_BUS.addListener(BspCore::onRecall);
        MinecraftForge.EVENT_BUS.addListener(BspCore::onTeamTeleport);
        MinecraftForge.EVENT_BUS.addListener(BspCore::onTeamCreated);
        MinecraftForge.EVENT_BUS.addListener(BspCore::onTeamJoined);
        MinecraftForge.EVENT_BUS.addListener(BspCore::onTeamLeft);
        MinecraftForge.EVENT_BUS.addListener(BspCore::onTeamLeaderChanged);
        MinecraftForge.EVENT_BUS.addListener(BspCore::onTeamDisbanded);
        // T-3: from now on BSP-Core's Team screen draws a TELEPORT key on every online teammate's row.
        BspTeams.teleportsHandledBy(BSPTeleport.MODID);
    }

    // Boards on the Score Screen (R8, D85)

    /** One line of a board this mod shows on BSP-Core's Score Screens: text at the left, value at the right, an icon before the text. */
    public record BoardEntry(Component text, @Nullable Component value, ItemStack icon) {
    }

    /**
     * Registers (or replaces) a board on the client. BSP-Core reads the supplier on the render thread about once a
     * second per screen that shows the board, so it must be cheap and never block; an empty list is "nothing yet".
     */
    public static void registerBoard(ResourceLocation id, Component title, Supplier<List<BoardEntry>> lines) {
        try {
            Boards.register(id, title, lines);
        } catch (LinkageError e) {
            // A BSP-Core jar before 1.4.0 has no BspBoards: everything else works, the board is just not offered.
            BSPTeleport.LOGGER.warn("This BSP-Core has no board API (needs 1.4.0 or newer); the {} board is not registered", id);
        }
    }

    /** The only code naming {@link BspBoards}, kept in its own class so an older BSP-Core fails to load just this. */
    private static final class Boards {
        static void register(ResourceLocation id, Component title, Supplier<List<BoardEntry>> lines) {
            BspBoards.register(id, title, () -> {
                List<BoardEntry> entries = lines.get();
                List<BspBoards.BoardLine> out = new ArrayList<>(entries.size());
                for (BoardEntry e : entries) {
                    out.add(BspBoards.BoardLine.of(e.text(), e.value(), e.icon()));
                }
                return out;
            });
        }
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

    /**
     * Whether a spot is within {@code distance} blocks, horizontally, of <em>any</em> totem being stolen right now,
     * whoever owns it (D91: team teleports must not land behind a thief). Same server naming and cache lag as above.
     */
    public static boolean nearAnySteal(net.minecraft.server.MinecraftServer server, String targetServer, String dimensionId, double x, double z, int distance) {
        String target = targetServer.isEmpty() ? TeleportConfig.serverName() : targetServer;
        for (BspTotems.StealAnywhere steal : BspTotems.stealsAnywhere(server)) {
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

    // Teams (T-1 to T-3, D91 and D92)

    /** A team as the band shows it: {@code colour} is the RGB of its chat colour, {@code members} in joining order. */
    public record TeamInfo(UUID id, String name, int colour, UUID leader, List<UUID> members) {
    }

    private static final int DEFAULT_TEAM_COLOUR = 0x19D3B0;

    public static Optional<TeamInfo> teamOf(net.minecraft.server.MinecraftServer server, UUID player) {
        return BspTeams.teamOf(server, player).map(t -> {
            Integer rgb = t.colour() == null ? null : t.colour().getColor();
            return new TeamInfo(t.id(), t.name(), rgb == null ? DEFAULT_TEAM_COLOUR : rgb, t.leader(), t.members());
        });
    }

    /** The members of a team with where they are, in joining order; servers as proxy names; empty when the team is gone. */
    public static List<TeamTeleports.Mate> teamMembers(net.minecraft.server.MinecraftServer server, UUID teamId) {
        List<TeamTeleports.Mate> out = new ArrayList<>();
        for (BspTeams.Member m : BspTeams.members(server, teamId)) {
            String where = m.onlineHere() ? TeleportConfig.serverName() : m.server().isEmpty() ? "" : proxyNameFor(m.server());
            out.add(new TeamTeleports.Mate(m.uuid(), m.name(), m.leader(), m.onlineHere(), where, m.seenMs()));
        }
        return out;
    }

    /** BSP-Core's Team screen: a member pressed TELEPORT on a teammate's row. Moved or refused with a reason, it is handled. */
    private static void onTeamTeleport(TeamEvent.TeleportRequest event) {
        ServerPlayer player = event.getPlayer();
        if (player == null || event.getTarget() == null) {
            return;
        }
        event.setHandled();
        TeamTeleports.go(player, event.getTarget());
    }

    private static void onTeamCreated(TeamEvent.Created event) {
        teamChanged(event, event.getLeader());
    }

    private static void onTeamJoined(TeamEvent.Joined event) {
        teamChanged(event, event.getPlayer());
    }

    private static void onTeamLeft(TeamEvent.Left event) {
        teamChanged(event, event.getPlayer());
    }

    private static void onTeamLeaderChanged(TeamEvent.LeaderChanged event) {
        teamChanged(event, null);
    }

    private static void onTeamDisbanded(TeamEvent.Disbanded event) {
        teamChanged(event, null);
    }

    /** Refreshes the band's TEAM tab for every member online here, plus the player the change was about (D92). */
    private static void teamChanged(TeamEvent event, @Nullable UUID also) {
        LinkedHashSet<UUID> who = new LinkedHashSet<>();
        if (also != null) {
            who.add(also);
        }
        BspTeams.team(event.getServer(), event.getTeamId()).ifPresent(t -> who.addAll(t.members()));
        com.mrgregles.bsp_teleport.band.BandService.refreshTeam(event.getServer(), who);
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
