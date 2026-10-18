package com.mrgregles.bsp_teleport.band;

import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.compat.BspCore;
import com.mrgregles.bsp_teleport.data.Home;
import com.mrgregles.bsp_teleport.data.HomeLimits;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.data.Warp;
import com.mrgregles.bsp_teleport.network.BandPackets;
import com.mrgregles.bsp_teleport.network.PlayerDirectory;
import com.mrgregles.bsp_teleport.request.TeleportRequests;
import com.mrgregles.bsp_teleport.team.TeamTeleports;
import com.mrgregles.bsp_teleport.teleport.TeleportActions;
import com.mrgregles.bsp_teleport.teleport.TeleportService;
import com.mrgregles.bsp_teleport.teleport.Teleports;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** The server side of the band screen: builds what it shows and carries out what it asks. */
public final class BandService {
    private BandService() {
    }

    /** Sends the player the current data; the client opens or refreshes the screen. */
    public static void open(ServerPlayer player) {
        BandPackets.sendData(player, build(player));
    }

    public static void handle(ServerPlayer player, BandAction action, String arg) {
        switch (action) {
            case OPEN -> open(player);
            case HOME -> TeleportActions.home(player, arg);
            case DEL_HOME -> {
                TeleportActions.delHome(player, arg);
                open(player);
            }
            case SET_HOME -> {
                TeleportActions.setHome(player, arg.isEmpty() ? "home" : arg);
                open(player);
            }
            case WARP -> TeleportActions.warp(player, arg);
            case TPA -> {
                TeleportRequests.send(player, arg, false);
                open(player);
            }
            case TPA_HERE -> {
                TeleportRequests.send(player, arg, true);
                open(player);
            }
            case ACCEPT -> TeleportRequests.accept(player);
            case DENY -> {
                TeleportRequests.deny(player);
                open(player);
            }
            case SPAWN -> TeleportActions.spawn(player);
            case BACK -> TeleportActions.back(player);
            case RTP -> TeleportActions.rtp(player, arg.isEmpty() ? null : arg);
            case ACCESS_CHOOSE -> com.mrgregles.bsp_teleport.access.AccessService.showChoose(player);
            case TOGGLE_IGNORE -> {
                TeleportRequests.toggleIgnore(player);
                open(player);
            }
            case TPNOW -> Teleports.payAndRetry(player);
            case ADMIN -> com.mrgregles.bsp_teleport.admin.AdminService.open(player);
            case HOME_ICON -> {
                TeleportActions.setHomeIcon(player, arg, com.mrgregles.bsp_teleport.util.Icons.heldItemId(player));
                open(player);
            }
            case TEAM_GO -> {
                UUID target;
                try {
                    target = UUID.fromString(arg);
                } catch (IllegalArgumentException e) {
                    return;
                }
                TeamTeleports.go(player, target);
            }
            case TEAM_REFRESH -> BandPackets.sendData(player, build(player).asRefresh());
        }
    }

    /** A team changed on this server (D92): every listed player online here gets fresh data, refresh-only. */
    public static void refreshTeam(net.minecraft.server.MinecraftServer server, Collection<UUID> players) {
        for (UUID uuid : players) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) {
                BandPackets.sendData(p, build(p).asRefresh());
            }
        }
    }

    public static BandData build(ServerPlayer player) {
        TeleportData data = TeleportData.get(player.server);
        int slots = HomeLimits.slots(player);
        boolean raid = BspCore.beingRaided(player);
        int raidDistance = TeleportConfig.getOr(TeleportConfig.RAID_HOME_DISTANCE, 150);

        List<BandData.HomeEntry> homes = new ArrayList<>();
        List<Home> mine = data.homesOf(player.getUUID());
        for (int i = 0; i < mine.size(); i++) {
            Home h = mine.get(i);
            Position p = h.position();
            homes.add(new BandData.HomeEntry(h.name(), p.server(), p.world(), (int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()),
                    i >= slots, raid && nearRaid(player, p, raidDistance), h.icon()));
        }
        List<BandData.WarpEntry> warps = new ArrayList<>();
        for (Warp w : TeleportActions.usableWarps(player)) {
            Position p = w.position();
            warps.add(new BandData.WarpEntry(w.name(), p.server(), p.world(), (int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()),
                    w.cost(), raid && nearRaid(player, p, raidDistance), w.icon()));
        }
        List<BandData.PlayerEntry> players = new ArrayList<>();
        for (ServerPlayer other : player.server.getPlayerList().getPlayers()) {
            if (other != player) {
                players.add(new BandData.PlayerEntry(other.getGameProfile().getName(), TeleportConfig.serverName()));
            }
        }
        for (String name : PlayerDirectory.allNames(player.server)) {
            String server = PlayerDirectory.serverOf(name);
            if (server != null && players.stream().noneMatch(e -> e.name().equalsIgnoreCase(name))) {
                players.add(new BandData.PlayerEntry(name, server));
            }
        }
        List<String> rtpServers = new ArrayList<>(PlayerDirectory.servers());
        rtpServers.remove(TeleportConfig.serverName());

        BandData.RequestEntry request = TeleportRequests.peek(player).map(r ->
                new BandData.RequestEntry(r.fromName(), r.fromServer(), r.here(), r.secondsLeft())).orElse(null);
        BandData.TeamEntry team = team(player, raidDistance);

        return new BandData(TeleportConfig.serverName(), slots, mine.size(), BspCore.extraHomeSlots(player), BspCore.swiftTravelLevel(player),
                (int) Teleports.cooldownLeft(player), raidDistance, raid, data.isIgnoring(player.getUUID()),
                TeleportActions.spawnPosition(player) != null, Teleports.back(player) != null,
                TeleportConfig.getOr(TeleportConfig.SKIP_COOLDOWN_COINS, 0), BspCore.coins(player),
                homes, warps, players, rtpServers, request, com.mrgregles.bsp_teleport.admin.AdminService.mayOpen(player),
                com.mrgregles.bsp_teleport.access.AccessService.enabled(), com.mrgregles.bsp_teleport.access.AccessService.bandEntries(player),
                false, team);
    }

    /** The TEAM tab's data (D92): the player's team and each teammate's state, or null when they are in none. */
    @javax.annotation.Nullable
    private static BandData.TeamEntry team(ServerPlayer player, int raidDistance) {
        var team = BspCore.teamOf(player.server, player.getUUID()).orElse(null);
        if (team == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        String leaderName = "";
        List<BandData.MateEntry> mates = new ArrayList<>();
        for (TeamTeleports.Mate m : BspCore.teamMembers(player.server, team.id())) {
            if (m.leader()) {
                leaderName = m.name();
            }
            if (m.uuid().equals(player.getUUID())) {
                continue;
            }
            String state = m.onlineHere() ? "here" : m.online() ? "far" : "off";
            boolean nearRaid = false;
            if (m.onlineHere()) {
                ServerPlayer there = player.server.getPlayerList().getPlayer(m.uuid());
                nearRaid = there != null && BspCore.nearAnySteal(player.server, "", there.serverLevel().dimension().location().toString(), there.getX(), there.getZ(), raidDistance);
            }
            boolean reachable = m.onlineHere() || (m.online() && com.mrgregles.bsp_teleport.network.Messenger.enabled());
            mates.add(new BandData.MateEntry(m.uuid().toString(), m.name(), m.leader(), state, m.server(),
                    "off".equals(state) ? TeamTeleports.ago(m.seenMs(), now) : "", reachable, nearRaid));
        }
        return new BandData.TeamEntry(team.name(), team.colour(), leaderName, mates);
    }

    private static boolean nearRaid(ServerPlayer player, Position p, int distance) {
        if (!p.isLocal()) {
            return false;
        }
        ServerLevel level = TeleportService.levelOf(player.server, p.world());
        return level != null && BspCore.nearRaid(player, level, p.x(), p.z(), distance);
    }
}
