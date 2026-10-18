package com.mrgregles.bsp_teleport.portal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Finds a portal's frame and opening from its core (D54). Pure logic, no Minecraft types, so it has a unit test.
 *
 * <p>Rules: every frame block reachable from the core through flat faces must lie in one vertical plane; the opening is
 * every enclosed cell of that plane (a flood fill from outside the frame's box finds what is not enclosed), so any
 * ring shape works, several openings included. The core counts as a frame block when it sits in the plane, and may
 * instead touch the frame from outside the plane. One core per frame.
 */
public final class PortalShape {
    /** What a position holds, as far as the scan cares. */
    public enum Cell { AIR, FRAME, CORE, FIELD, DOOR, OTHER }

    /** The horizontal axis the opening runs along: X spans x and y (its faces look north and south), Z spans z and y. */
    public enum Axis { X, Z }

    public interface Lookup {
        Cell at(int x, int y, int z);
    }

    public record Pos(int x, int y, int z) {
        Pos offset(int dx, int dy, int dz) {
            return new Pos(x + dx, y + dy, z + dz);
        }
    }

    /**
     * @param frame   the frame blocks in the plane (the core included when it is in the plane)
     * @param opening the enclosed cells; empty unless the status is {@link PortalStatus#NO_DESTINATION} (the shape is fine)
     * @param minU    along the axis (x or z), with the plane's fixed coordinate in {@code fixed}
     */
    public record Result(PortalStatus status, Axis axis, Set<Pos> frame, Set<Pos> opening, int minU, int maxU, int minY, int maxY, int fixed) {
        public int width() {
            return frame.isEmpty() ? 0 : maxU - minU + 1;
        }

        public int height() {
            return frame.isEmpty() ? 0 : maxY - minY + 1;
        }

        /** Whether the position is one of the frame blocks or opening cells, or within a block of them. */
        public boolean touches(Pos p) {
            if (frame.isEmpty()) {
                return false;
            }
            int u = axis == Axis.X ? p.x() : p.z();
            int f = axis == Axis.X ? p.z() : p.x();
            return Math.abs(f - fixed) <= 1 && u >= minU - 1 && u <= maxU + 1 && p.y() >= minY - 1 && p.y() <= maxY + 1;
        }
    }

    private static final int[][] SIDES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private PortalShape() {
    }

    private static Result fail(PortalStatus status) {
        return new Result(status, Axis.X, Set.of(), Set.of(), 0, 0, 0, 0, 0);
    }

    /**
     * @param maxSize    most blocks across or tall the frame may be
     * @param maxOpening most enclosed cells
     */
    public static Result scan(Lookup world, Pos core, int maxSize, int maxOpening) {
        // 1. Everything reachable from the core through flat faces, frame or core.
        Set<Pos> seen = new HashSet<>();
        Set<Pos> frame = new LinkedHashSet<>();
        int cores = 0;
        Deque<Pos> queue = new ArrayDeque<>();
        queue.add(core);
        seen.add(core);
        int limit = (maxSize + 2) * (maxSize + 2) * 2;
        while (!queue.isEmpty()) {
            Pos p = queue.poll();
            Cell cell = world.at(p.x(), p.y(), p.z());
            if (cell == Cell.CORE) {
                cores++;
            } else if (cell == Cell.FRAME) {
                frame.add(p);
            } else {
                continue;
            }
            if (seen.size() > limit) {
                return fail(PortalStatus.TOO_BIG);
            }
            for (int[] d : SIDES) {
                Pos n = p.offset(d[0], d[1], d[2]);
                if (Math.abs(n.x() - core.x()) > maxSize + 1 || Math.abs(n.y() - core.y()) > maxSize + 1 || Math.abs(n.z() - core.z()) > maxSize + 1) {
                    continue;
                }
                if (seen.add(n)) {
                    Cell c = world.at(n.x(), n.y(), n.z());
                    if (c == Cell.FRAME || c == Cell.CORE) {
                        queue.add(n);
                    }
                }
            }
        }
        if (cores > 1) {
            return fail(PortalStatus.TWO_CORES);
        }
        if (frame.isEmpty()) {
            return fail(PortalStatus.NO_FRAME);
        }
        // 2. The plane.
        Set<Integer> xs = new HashSet<>(), zs = new HashSet<>();
        for (Pos p : frame) {
            xs.add(p.x());
            zs.add(p.z());
        }
        Axis axis;
        int fixed;
        if (xs.size() == 1 && zs.size() == 1) {
            return fail(PortalStatus.OPEN_FRAME);
        } else if (zs.size() == 1) {
            axis = Axis.X;
            fixed = zs.iterator().next();
        } else if (xs.size() == 1) {
            axis = Axis.Z;
            fixed = xs.iterator().next();
        } else {
            return fail(PortalStatus.NOT_FLAT);
        }
        Set<Pos> ring = new LinkedHashSet<>(frame);
        if ((axis == Axis.X ? core.z() : core.x()) == fixed) {
            ring.add(core);
        }
        // 3. The box.
        int minU = Integer.MAX_VALUE, maxU = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (Pos p : ring) {
            int u = axis == Axis.X ? p.x() : p.z();
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minY = Math.min(minY, p.y());
            maxY = Math.max(maxY, p.y());
        }
        if (maxU - minU + 1 > maxSize || maxY - minY + 1 > maxSize) {
            return fail(PortalStatus.TOO_BIG);
        }
        // 4. Flood from outside the box; what the flood never reaches is enclosed.
        int w = maxU - minU + 3, h = maxY - minY + 3;
        boolean[][] wall = new boolean[w][h], reached = new boolean[w][h];
        for (Pos p : ring) {
            int u = axis == Axis.X ? p.x() : p.z();
            wall[u - minU + 1][p.y() - minY + 1] = true;
        }
        Deque<int[]> flood = new ArrayDeque<>();
        flood.add(new int[]{0, 0});
        reached[0][0] = true;
        int[][] four = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!flood.isEmpty()) {
            int[] c = flood.poll();
            for (int[] d : four) {
                int u = c[0] + d[0], v = c[1] + d[1];
                if (u < 0 || v < 0 || u >= w || v >= h || wall[u][v] || reached[u][v]) {
                    continue;
                }
                reached[u][v] = true;
                flood.add(new int[]{u, v});
            }
        }
        Set<Pos> opening = new LinkedHashSet<>();
        boolean blocked = false;
        for (int u = 1; u < w - 1; u++) {
            for (int v = 1; v < h - 1; v++) {
                if (wall[u][v] || reached[u][v]) {
                    continue;
                }
                int au = u + minU - 1, y = v + minY - 1;
                Pos p = axis == Axis.X ? new Pos(au, y, fixed) : new Pos(fixed, y, au);
                Cell cell = world.at(p.x(), p.y(), p.z());
                if (cell == Cell.AIR || cell == Cell.FIELD || cell == Cell.DOOR) {
                    opening.add(p);
                } else {
                    blocked = true;
                }
            }
        }
        if (opening.isEmpty() && !blocked) {
            return fail(PortalStatus.OPEN_FRAME);
        }
        if (blocked) {
            return fail(PortalStatus.BLOCKED);
        }
        if (opening.size() > maxOpening) {
            return fail(PortalStatus.TOO_BIG);
        }
        return new Result(PortalStatus.NO_DESTINATION, axis, ring, opening, minU, maxU, minY, maxY, fixed);
    }
}
