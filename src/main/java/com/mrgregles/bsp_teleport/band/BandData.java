package com.mrgregles.bsp_teleport.band;

import net.minecraft.network.FriendlyByteBuf;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Everything the band screen shows, built on the server and sent whole. */
public record BandData(String server, int slots, int used, int extraHomesLevel, int swiftLevel, int cooldownLeft, int raidDistance,
                       boolean raid, boolean ignoring, boolean hasSpawn, boolean hasBack, int skipCoins, int coins,
                       List<HomeEntry> homes, List<WarpEntry> warps, List<PlayerEntry> players, List<String> rtpServers,
                       @Nullable RequestEntry request, boolean admin, boolean accessOn, List<AccessEntry> access) {

    public record HomeEntry(String name, String server, String world, int x, int y, int z, boolean locked, boolean nearRaid, String icon) {
    }

    public record WarpEntry(String name, String server, String world, int x, int y, int z, int cost, boolean nearRaid, String icon) {
    }

    public record PlayerEntry(String name, String server) {
    }

    public record RequestEntry(String from, String server, boolean here, int secondsLeft) {
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
    }

    public static BandData decode(FriendlyByteBuf buf) {
        String server = buf.readUtf();
        int slots = buf.readVarInt(), used = buf.readVarInt(), extra = buf.readVarInt(), swift = buf.readVarInt(), cooldown = buf.readVarInt(), raidDistance = buf.readVarInt();
        boolean raid = buf.readBoolean(), ignoring = buf.readBoolean(), hasSpawn = buf.readBoolean(), hasBack = buf.readBoolean();
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
            warps.add(new WarpEntry(wn, ws, ww, buf.readInt(), buf.readInt(), buf.readInt(), buf.readVarInt(), buf.readBoolean(), wi));
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
        return new BandData(server, slots, used, extra, swift, cooldown, raidDistance, raid, ignoring, hasSpawn, hasBack, skipCoins, coins, homes, warps, players, rtp, request, admin, accessOn, access);
    }
}
