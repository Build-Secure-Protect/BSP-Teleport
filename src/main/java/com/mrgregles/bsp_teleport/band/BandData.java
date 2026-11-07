package com.mrgregles.bsp_teleport.band;

import net.minecraft.network.FriendlyByteBuf;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything the band screen shows, built on the server and sent whole. B47: {@code spawnCost} is the network spawn
 * warp's price (0 when there is none or it is free), {@code spawnPieces} whether in bank pieces, {@code spawnAfford}
 * whether the player holds it with the warp's money rules. B49: prices are the warps' own, so admins, who travel free
 * (B29), still see them.
 */
public record BandData(String server, int slots, int used, int extraHomesLevel, int swiftLevel, int cooldownLeft, int raidDistance,
                       boolean raid, boolean ignoring, boolean hasSpawn, boolean hasBack, int spawnCost, boolean spawnPieces, boolean spawnAfford,
                       int skipCoins, int coins,
                       List<HomeEntry> homes, List<WarpEntry> warps, List<PlayerEntry> players, List<String> rtpServers,
                       @Nullable RequestEntry request, boolean admin, boolean accessOn, List<AccessEntry> access,
                       boolean refreshOnly, @Nullable TeamEntry team) {

    /** A refresh for a screen that is already open; the client ignores it when the screen is closed (D92). */
    public BandData asRefresh() {
        return new BandData(server, slots, used, extraHomesLevel, swiftLevel, cooldownLeft, raidDistance, raid, ignoring, hasSpawn, hasBack,
                spawnCost, spawnPieces, spawnAfford, skipCoins, coins, homes, warps, players, rtpServers, request, admin, accessOn, access, true, team);
    }

    public record HomeEntry(String name, String server, String world, int x, int y, int z, boolean locked, boolean nearRaid, String icon) {
    }

    /**
     * @param opensAt D150: -1 with no open hours, -2 when open now, else the minute of the day it next opens (server's zone)
     * @param cost    the warp's own price; B49: admins see it too, and {@code afford} is true for them (B29)
     * @param pieces  D163: the cost is in bank pieces, else in coins
     * @param afford  whether the player holds the cost (and the warp's money rules) in the right currency now */
    public record WarpEntry(String name, String server, String world, int x, int y, int z, int cost, boolean nearRaid, String icon, int opensAt,
                            boolean pieces, boolean afford) {
    }

    public record PlayerEntry(String name, String server) {
    }

    public record RequestEntry(String from, String server, boolean here, int secondsLeft) {
    }

    /**
     * The player's team for the TEAM tab (D92): colour is the team's chat colour as RGB; mates in joining order.
     * {@code alliance} is the alliance's name, "" when the team is in none (D167), and {@code allies} its other teams' players.
     */
    public record TeamEntry(String name, int colour, String leader, List<MateEntry> mates, String alliance, List<AllyEntry> allies) {
    }

    /**
     * One ally (D167): their team's name and colour (RGB), state here / far / off as for a mate, {@code dimension} ""
     * until known, {@code coords} "x y z" only when their team shares them, else "".
     */
    public record AllyEntry(String uuid, String name, String teamName, int colour, String state, String server, String dimension, String coords,
                            String seen, boolean reachable, boolean nearRaid) {
    }

    /**
     * One teammate: state is here, far (online on {@code server}) or off (seen says when); reachable is false when
     * the teammate is on a server this one cannot reach; nearRaid when a steal runs within the raid distance of them.
     */
    public record MateEntry(String uuid, String name, boolean leader, String state, String server, String seen, boolean reachable, boolean nearRaid) {
    }

    /** One restricted server on the MORE tab (D80): state is main, full, rent or none; token is the server's symbol (0 for none). */
    public record AccessEntry(String server, String state, String left, int token) {
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(server);
        buf.writeVarInt(slots);
        buf.writeVarInt(used);
        buf.writeVarInt(extraHomesLevel);
        buf.writeVarInt(swiftLevel);
        buf.writeVarInt(cooldownLeft);
        buf.writeVarInt(raidDistance);
        buf.writeBoolean(raid);
        buf.writeBoolean(ignoring);
        buf.writeBoolean(hasSpawn);
        buf.writeBoolean(hasBack);
        buf.writeVarInt(spawnCost);
        buf.writeBoolean(spawnPieces);
        buf.writeBoolean(spawnAfford);
        buf.writeVarInt(skipCoins);
        buf.writeVarInt(coins);
        buf.writeVarInt(homes.size());
        for (HomeEntry h : homes) {
            buf.writeUtf(h.name()).writeUtf(h.server()).writeUtf(h.world()).writeUtf(h.icon());
            buf.writeInt(h.x());
            buf.writeInt(h.y());
            buf.writeInt(h.z());
            buf.writeBoolean(h.locked());
            buf.writeBoolean(h.nearRaid());
        }
        buf.writeVarInt(warps.size());
        for (WarpEntry w : warps) {
            buf.writeUtf(w.name()).writeUtf(w.server()).writeUtf(w.world()).writeUtf(w.icon());
            buf.writeInt(w.x());
            buf.writeInt(w.y());
            buf.writeInt(w.z());
            buf.writeVarInt(w.cost());
            buf.writeBoolean(w.nearRaid());
            buf.writeVarInt(w.opensAt());
            buf.writeBoolean(w.pieces());
            buf.writeBoolean(w.afford());
        }
        buf.writeVarInt(players.size());
        for (PlayerEntry p : players) {
            buf.writeUtf(p.name()).writeUtf(p.server());
        }
        buf.writeVarInt(rtpServers.size());
        rtpServers.forEach(buf::writeUtf);
        buf.writeBoolean(request != null);
        if (request != null) {
            buf.writeUtf(request.from()).writeUtf(request.server());
            buf.writeBoolean(request.here());
            buf.writeVarInt(request.secondsLeft());
        }
        buf.writeBoolean(admin);
        buf.writeBoolean(accessOn);
        buf.writeVarInt(access.size());
        for (AccessEntry a : access) {
            buf.writeUtf(a.server()).writeUtf(a.state()).writeUtf(a.left());
            buf.writeVarInt(a.token());
        }
        buf.writeBoolean(refreshOnly);
        buf.writeBoolean(team != null);
        if (team != null) {
            buf.writeUtf(team.name()).writeUtf(team.leader());
            buf.writeInt(team.colour());
            buf.writeVarInt(team.mates().size());
            for (MateEntry m : team.mates()) {
                buf.writeUtf(m.uuid()).writeUtf(m.name()).writeUtf(m.state()).writeUtf(m.server()).writeUtf(m.seen());
                buf.writeBoolean(m.leader());
                buf.writeBoolean(m.reachable());
                buf.writeBoolean(m.nearRaid());
            }
            buf.writeUtf(team.alliance());
            buf.writeVarInt(team.allies().size());
            for (AllyEntry a : team.allies()) {
                buf.writeUtf(a.uuid()).writeUtf(a.name()).writeUtf(a.teamName()).writeUtf(a.state()).writeUtf(a.server())
                        .writeUtf(a.dimension()).writeUtf(a.coords()).writeUtf(a.seen());
                buf.writeInt(a.colour());
                buf.writeBoolean(a.reachable());
                buf.writeBoolean(a.nearRaid());
            }
        }
    }

    public static BandData decode(FriendlyByteBuf buf) {
        String server = buf.readUtf();
        int slots = buf.readVarInt(), used = buf.readVarInt(), extra = buf.readVarInt(), swift = buf.readVarInt(), cooldown = buf.readVarInt(), raidDistance = buf.readVarInt();
        boolean raid = buf.readBoolean(), ignoring = buf.readBoolean(), hasSpawn = buf.readBoolean(), hasBack = buf.readBoolean();
        int spawnCost = buf.readVarInt();
        boolean spawnPieces = buf.readBoolean(), spawnAfford = buf.readBoolean();
        int skipCoins = buf.readVarInt(), coins = buf.readVarInt();
        int n = buf.readVarInt();
        List<HomeEntry> homes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String hn = buf.readUtf(), hs = buf.readUtf(), hw = buf.readUtf(), hi = buf.readUtf();
            homes.add(new HomeEntry(hn, hs, hw, buf.readInt(), buf.readInt(), buf.readInt(), buf.readBoolean(), buf.readBoolean(), hi));
        }
        n = buf.readVarInt();
        List<WarpEntry> warps = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String wn = buf.readUtf(), ws = buf.readUtf(), ww = buf.readUtf(), wi = buf.readUtf();
            int wx = buf.readInt(), wy = buf.readInt(), wz = buf.readInt(), cost = buf.readVarInt();
            boolean nearRaid = buf.readBoolean();
            int opensAt = buf.readVarInt();
            warps.add(new WarpEntry(wn, ws, ww, wx, wy, wz, cost, nearRaid, wi, opensAt, buf.readBoolean(), buf.readBoolean()));
        }
        n = buf.readVarInt();
        List<PlayerEntry> players = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            players.add(new PlayerEntry(buf.readUtf(), buf.readUtf()));
        }
        n = buf.readVarInt();
        List<String> rtp = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rtp.add(buf.readUtf());
        }
        RequestEntry request = buf.readBoolean() ? new RequestEntry(buf.readUtf(), buf.readUtf(), buf.readBoolean(), buf.readVarInt()) : null;
        boolean admin = buf.readBoolean();
        boolean accessOn = buf.readBoolean();
        n = buf.readVarInt();
        List<AccessEntry> access = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String as = buf.readUtf(), st = buf.readUtf(), lf = buf.readUtf();
            access.add(new AccessEntry(as, st, lf, buf.readVarInt()));
        }
        boolean refreshOnly = buf.readBoolean();
        TeamEntry team = null;
        if (buf.readBoolean()) {
            String tn = buf.readUtf(), tl = buf.readUtf();
            int colour = buf.readInt();
            n = buf.readVarInt();
            List<MateEntry> mates = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                String mu = buf.readUtf(), mn = buf.readUtf(), ms = buf.readUtf(), msv = buf.readUtf(), mseen = buf.readUtf();
                mates.add(new MateEntry(mu, mn, buf.readBoolean(), ms, msv, mseen, buf.readBoolean(), buf.readBoolean()));
            }
            String alliance = buf.readUtf();
            n = buf.readVarInt();
            List<AllyEntry> allies = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                String au = buf.readUtf(), an = buf.readUtf(), at = buf.readUtf(), as = buf.readUtf(), asv = buf.readUtf(), ad = buf.readUtf(), ac = buf.readUtf(), aseen = buf.readUtf();
                int acolour = buf.readInt();
                allies.add(new AllyEntry(au, an, at, acolour, as, asv, ad, ac, aseen, buf.readBoolean(), buf.readBoolean()));
            }
            team = new TeamEntry(tn, colour, tl, mates, alliance, allies);
        }
        return new BandData(server, slots, used, extra, swift, cooldown, raidDistance, raid, ignoring, hasSpawn, hasBack, spawnCost, spawnPieces, spawnAfford, skipCoins, coins, homes, warps, players, rtp, request, admin, accessOn, access, refreshOnly, team);
    }
}
