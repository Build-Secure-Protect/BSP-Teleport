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
import com.mrgregles.bsp_teleport.teleport.TeleportActions;
import com.mrgregles.bsp_teleport.teleport.TeleportService;
import com.mrgregles.bsp_teleport.teleport.Teleports;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

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
            case TOGGLE_IGNORE -> {
                TeleportRequests.toggleIgnore(player);
                open(player);
            }
            case TPNOW -> Teleports.payAndRetry(player);
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
                    i >= slots, raid && nearRaid(player, p, raidDistance)));
        }
        List<BandData.WarpEntry> warps = new ArrayList<>();
        for (Warp w : TeleportActions.usableWarps(player)) {
            Position p = w.position();
            warps.add(new BandData.WarpEntry(w.name(), p.server(), p.world(), (int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()),
                    w.cost(), raid && nearRaid(player, p, raidDistance)));
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

        return new BandData(TeleportConfig.serverName(), slots, mine.size(), BspCore.extraHomeSlots(player), BspCore.swiftTravelLevel(player),
                (int) Teleports.cooldownLeft(player), raidDistance, raid, data.isIgnoring(player.getUUID()),
                TeleportActions.spawnPosition(player) != null, Teleports.back(player) != null,
                TeleportConfig.getOr(TeleportConfig.SKIP_COOLDOWN_COINS, 0), BspCore.coins(player),
                homes, warps, players, rtpServers, request, com.mrgregles.bsp_teleport.admin.AdminService.mayOpen(player));
    }

    private static boolean nearRaid(ServerPlayer player, Position p, int distance) {
        if (!p.isLocal()) {
            return false;
        }
        ServerLevel level = TeleportService.levelOf(player.server, p.world());
        return level != null && BspCore.nearRaid(player, level, p.x(), p.z(), distance);
    }
}
