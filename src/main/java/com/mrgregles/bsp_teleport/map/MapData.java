package com.mrgregles.bsp_teleport.map;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * D201: what the band's MAP tab draws, built on the server for one player and sent when the tab opens.
 *
 * @param here     this server's name
 * @param cols     the grid's width in cells (0 with no linked worlds)
 * @param rows     the grid's height in cells
 * @param servers  every server shown: on the grid, off it, or with no route
 * @param bridges  linked worlds side by side
 * @param stubs    crossings to a world not beside this one (far-side half tiles)
 * @param portals  portal lines that touch a server off the grid, from the server the portal stands on
 */
public record MapData(String here, int cols, int rows, List<Server> servers, List<Bridge> bridges, List<Stub> stubs, List<Portal> portals) {
    /** Caps on what a client reads, so a bad packet cannot ask for huge lists. */
    private static final int MAX = 256, MAX_NAMES = 16;

    public enum Kind {
        /** On the grid of linked worlds. */
        GRID,
        /** Off the grid, reached by warps or a portal. */
        OFF,
        /** Nothing leads there. */
        NONE
    }

    /**
     * @param size      the overworld border's side in blocks, 0 when unknown or none
     * @param up        whether the server answered lately
     * @param online    players on it
     * @param crossings where its border sides lead, as {@link MapLayout.Side} ordinals with the server
     * @param warps     the names of warps on it this player can use (at most 16)
     */
    public record Server(String name, Kind kind, int x, int y, int size, boolean up, int online, List<Crossing> crossings, List<String> warps) {
    }

    public record Crossing(int side, String to) {
    }

    public record Bridge(String a, String b) {
    }

    public record Stub(String server, int side, String to) {
    }

    public record Portal(String from, String to) {
    }

    public Server server(String name) {
        for (Server s : servers) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        return null;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(here);
        buf.writeVarInt(cols);
        buf.writeVarInt(rows);
        buf.writeVarInt(servers.size());
        for (Server s : servers) {
            buf.writeUtf(s.name());
            buf.writeEnum(s.kind());
            buf.writeVarInt(s.x());
            buf.writeVarInt(s.y());
            buf.writeVarInt(s.size());
            buf.writeBoolean(s.up());
            buf.writeVarInt(s.online());
            buf.writeVarInt(s.crossings().size());
            for (Crossing c : s.crossings()) {
                buf.writeVarInt(c.side());
                buf.writeUtf(c.to());
            }
            buf.writeVarInt(s.warps().size());
            s.warps().forEach(buf::writeUtf);
        }
        buf.writeVarInt(bridges.size());
        for (Bridge b : bridges) {
            buf.writeUtf(b.a());
            buf.writeUtf(b.b());
        }
        buf.writeVarInt(stubs.size());
        for (Stub s : stubs) {
            buf.writeUtf(s.server());
            buf.writeVarInt(s.side());
            buf.writeUtf(s.to());
        }
        buf.writeVarInt(portals.size());
        for (Portal p : portals) {
            buf.writeUtf(p.from());
            buf.writeUtf(p.to());
        }
    }

    public static MapData decode(FriendlyByteBuf buf) {
        String here = buf.readUtf();
        int cols = buf.readVarInt(), rows = buf.readVarInt();
        int n = Math.min(buf.readVarInt(), MAX);
        List<Server> servers = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String name = buf.readUtf();
            Kind kind = buf.readEnum(Kind.class);
            int x = buf.readVarInt(), y = buf.readVarInt(), size = buf.readVarInt();
            boolean up = buf.readBoolean();
            int online = buf.readVarInt();
            int c = Math.min(buf.readVarInt(), 4);
            List<Crossing> crossings = new ArrayList<>(c);
            for (int j = 0; j < c; j++) {
                crossings.add(new Crossing(buf.readVarInt(), buf.readUtf()));
            }
            int w = Math.min(buf.readVarInt(), MAX_NAMES);
            List<String> warps = new ArrayList<>(w);
            for (int j = 0; j < w; j++) {
                warps.add(buf.readUtf());
            }
            servers.add(new Server(name, kind, x, y, size, up, online, crossings, warps));
        }
        int b = Math.min(buf.readVarInt(), MAX);
        List<Bridge> bridges = new ArrayList<>(b);
        for (int i = 0; i < b; i++) {
            bridges.add(new Bridge(buf.readUtf(), buf.readUtf()));
        }
        int s = Math.min(buf.readVarInt(), MAX);
        List<Stub> stubs = new ArrayList<>(s);
        for (int i = 0; i < s; i++) {
            stubs.add(new Stub(buf.readUtf(), buf.readVarInt(), buf.readUtf()));
        }
        int p = Math.min(buf.readVarInt(), MAX);
        List<Portal> portals = new ArrayList<>(p);
        for (int i = 0; i < p; i++) {
            portals.add(new Portal(buf.readUtf(), buf.readUtf()));
        }
        return new MapData(here, cols, rows, servers, bridges, stubs, portals);
    }
}
