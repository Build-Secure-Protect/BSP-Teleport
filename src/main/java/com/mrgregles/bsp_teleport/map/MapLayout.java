package com.mrgregles.bsp_teleport.map;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * D201: lays out the worlds joined at their borders as a grid, the same for every viewer. Pure, so it is unit tested.
 *
 * <p>It starts from the world named in {@code first} (the config's {@code map.firstServer}), else the one whose server
 * name sorts first, and walks the links breadth first, east and south before west and north so the grid grows right
 * and down: each link places its neighbour one cell in the direction of the side crossed. A link whose neighbour is already placed somewhere else, or
 * whose cell is taken, becomes a far-side stub at both ends. Separate groups of linked worlds sit side by side, one
 * empty column apart. Only worlds with at least one link are placed.
 */
public final class MapLayout {
    /** A side of a square border; north is towards negative z, as in Minecraft. */
    public enum Side {
        NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);

        public final int dx, dy;

        Side(int dx, int dy) {
            this.dx = dx;
            this.dy = dy;
        }

        public Side opposite() {
            return values()[(ordinal() + 2) % 4];
        }

        /** "north" and so on, as Region Guard writes it; null for anything else. */
        public static Side parse(String text) {
            for (Side s : values()) {
                if (s.name().equalsIgnoreCase(text == null ? "" : text.trim())) {
                    return s;
                }
            }
            return null;
        }
    }

    /** One edge of another server's world. */
    public record Link(String server, Side side) {
    }

    /** A server's overworld: its border size (0 for none) and the links its admin made, by side. */
    public record World(String server, int size, Map<Side, Link> links) {
    }

    /** A placed world. */
    public record Cell(String server, int size, int x, int y) {
    }

    /** Two worlds side by side whose shared border links them. */
    public record Bridge(String a, String b) {
    }

    /** A crossing to a world that is not beside this one: a half tile on {@code side} of {@code server}, leading to {@code to}. */
    public record Stub(String server, Side side, String to) {
    }

    /** Where a side of a world leads, for the footer. */
    public record Crossing(Side side, String to) {
    }

    public record Result(List<Cell> cells, List<Bridge> bridges, List<Stub> stubs, Map<String, List<Crossing>> crossings, int cols, int rows) {
        public boolean isEmpty() {
            return cells.isEmpty();
        }
    }

    private record Edge(String a, Side as, String b, Side bs) {
    }

    /** East and south first, so a grid that wraps round is drawn from its first world rightwards and down. */
    private static final Side[] WALK = {Side.EAST, Side.SOUTH, Side.WEST, Side.NORTH};

    private MapLayout() {
    }

    public static Result layout(List<World> worlds) {
        return layout(worlds, "");
    }

    /** @param first the server to start from (top left of its group), or empty for the first by name */
    public static Result layout(List<World> worlds, String first) {
        Map<String, Integer> sizes = new HashMap<>();
        for (World w : worlds) {
            sizes.put(w.server(), Math.max(0, w.size()));
        }
        // Each link once, from whichever end recorded it; a side holds one link, so the first one read wins.
        List<Edge> edges = new ArrayList<>();
        Set<String> usedSides = new HashSet<>();
        List<World> sorted = new ArrayList<>(worlds);
        sorted.sort(Comparator.comparing(World::server, String.CASE_INSENSITIVE_ORDER));
        for (World w : sorted) {
            new TreeMap<>(w.links()).forEach((side, link) -> {
                if (link == null || link.side() == null || link.server().equalsIgnoreCase(w.server())) {
                    return;
                }
                String here = w.server() + "|" + side, there = link.server() + "|" + link.side();
                if (usedSides.contains(here) || usedSides.contains(there)) {
                    return;
                }
                usedSides.add(here);
                usedSides.add(there);
                edges.add(new Edge(w.server(), side, link.server(), link.side()));
                sizes.putIfAbsent(link.server(), 0);
            });
        }
        // Each world's crossings by side.
        Map<String, Map<Side, Edge>> bySide = new HashMap<>();
        for (Edge e : edges) {
            bySide.computeIfAbsent(e.a(), k -> new EnumMap<>(Side.class)).put(e.as(), e);
            bySide.computeIfAbsent(e.b(), k -> new EnumMap<>(Side.class)).put(e.bs(), e);
        }
        Map<String, List<Crossing>> crossings = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        bySide.forEach((server, sides) -> {
            List<Crossing> list = new ArrayList<>();
            sides.forEach((side, e) -> list.add(new Crossing(side, e.a().equals(server) && e.as() == side ? e.b() : e.a())));
            crossings.put(server, list);
        });

        List<String> names = new ArrayList<>(bySide.keySet());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        names.stream().filter(n -> n.equalsIgnoreCase(first == null ? "" : first.trim())).findFirst().ifPresent(n -> {
            names.remove(n);
            names.add(0, n);
        });
        Map<String, int[]> placed = new HashMap<>();
        Set<Edge> done = new HashSet<>();
        List<Bridge> bridges = new ArrayList<>();
        List<Stub> stubs = new ArrayList<>();
        List<Cell> cells = new ArrayList<>();
        int offsetX = 0, rows = 0;
        for (String start : names) {
            if (placed.containsKey(start)) {
                continue;
            }
            // One group: breadth first from its first name, in its own coordinates.
            Map<String, int[]> local = new LinkedHashMap<>();
            Map<Long, String> taken = new HashMap<>();
            local.put(start, new int[] {0, 0});
            taken.put(key(0, 0), start);
            Deque<String> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                String at = queue.poll();
                int[] p = local.get(at);
                for (Side side : WALK) {
                    Edge e = bySide.get(at).get(side);
                    if (e == null || done.contains(e)) {
                        continue;
                    }
                    String other = e.a().equals(at) && e.as() == side ? e.b() : e.a();
                    Side otherSide = e.a().equals(at) && e.as() == side ? e.bs() : e.as();
                    int nx = p[0] + side.dx, ny = p[1] + side.dy;
                    int[] o = local.get(other);
                    if (o == null && !placed.containsKey(other) && !taken.containsKey(key(nx, ny))) {
                        local.put(other, new int[] {nx, ny});
                        taken.put(key(nx, ny), other);
                        queue.add(other);
                        bridges.add(new Bridge(at, other));
                    } else if (o != null && o[0] == nx && o[1] == ny) {
                        bridges.add(new Bridge(at, other));
                    } else {
                        stubs.add(new Stub(at, side, other));
                        stubs.add(new Stub(other, otherSide, at));
                    }
                    done.add(e);
                }
            }
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
            for (int[] p : local.values()) {
                minX = Math.min(minX, p[0]);
                minY = Math.min(minY, p[1]);
                maxX = Math.max(maxX, p[0]);
                maxY = Math.max(maxY, p[1]);
            }
            for (Map.Entry<String, int[]> en : local.entrySet()) {
                int[] p = new int[] {en.getValue()[0] - minX + offsetX, en.getValue()[1] - minY};
                placed.put(en.getKey(), p);
                cells.add(new Cell(en.getKey(), sizes.getOrDefault(en.getKey(), 0), p[0], p[1]));
            }
            offsetX += maxX - minX + 2;
            rows = Math.max(rows, maxY - minY + 1);
        }
        cells.sort(Comparator.comparingInt(Cell::y).thenComparingInt(Cell::x));
        return new Result(cells, bridges, stubs, crossings, Math.max(0, offsetX - 1), rows);
    }

    private static long key(int x, int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }
}
