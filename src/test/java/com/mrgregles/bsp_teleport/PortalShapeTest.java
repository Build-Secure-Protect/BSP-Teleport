package com.mrgregles.bsp_teleport;

import com.mrgregles.bsp_teleport.portal.PortalShape;
import com.mrgregles.bsp_teleport.portal.PortalShape.Cell;
import com.mrgregles.bsp_teleport.portal.PortalShape.Pos;
import com.mrgregles.bsp_teleport.portal.PortalStatus;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The frame rules of D54, on small worlds drawn as text. '#' frame, 'C' core, '.' air, 'X' another block. */
class PortalShapeTest {
    private static final int MAX = 24, MAX_OPENING = 300;

    /** A drawing in the plane z = 0 (axis X): rows top to bottom are y descending, columns are x. */
    private static Map<Pos, Cell> plane(String... rows) {
        Map<Pos, Cell> world = new HashMap<>();
        for (int r = 0; r < rows.length; r++) {
            int y = rows.length - 1 - r;
            for (int x = 0; x < rows[r].length(); x++) {
                char ch = rows[r].charAt(x);
                Cell cell = ch == '#' ? Cell.FRAME : ch == 'C' ? Cell.CORE : ch == 'X' ? Cell.OTHER : Cell.AIR;
                if (cell != Cell.AIR) {
                    world.put(new Pos(x, y, 0), cell);
                }
            }
        }
        return world;
    }

    private static Pos core(Map<Pos, Cell> world) {
        return world.entrySet().stream().filter(e -> e.getValue() == Cell.CORE).map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    private static PortalShape.Result scan(Map<Pos, Cell> world) {
        return PortalShape.scan((x, y, z) -> world.getOrDefault(new Pos(x, y, z), Cell.AIR), core(world), MAX, MAX_OPENING);
    }

    @Test
    void rectangleWithTheCoreInTheRing() {
        PortalShape.Result r = scan(plane(
                "#####",
                "#...#",
                "#...#",
                "#...#",
                "##C##"));
        assertEquals(PortalStatus.NO_DESTINATION, r.status());
        assertEquals(PortalShape.Axis.X, r.axis());
        assertEquals(9, r.opening().size());
        assertEquals(5, r.width());
        assertEquals(5, r.height());
        assertEquals(16, r.frame().size());
        assertTrue(r.opening().contains(new Pos(2, 2, 0)));
    }

    @Test
    void anyShapeWorksIncludingTwoOpenings() {
        PortalShape.Result r = scan(plane(
                "####....",
                "#..#....",
                "#..#####",
                "#......#",
                "#.###..#",
                "#.#.#..#",
                "#.###..#",
                "####C###"));
        assertEquals(PortalStatus.NO_DESTINATION, r.status());
        // The L-shaped opening plus the one-cell hole inside the small inner ring.
        assertEquals(2 + 2 + 6 + 3 + 4 + 3, r.opening().size());
        assertTrue(r.opening().contains(new Pos(3, 2, 0)));
    }

    @Test
    void openFrameHasNoEnclosedAir() {
        assertEquals(PortalStatus.OPEN_FRAME, scan(plane(
                "###.#",
                "#...#",
                "##C##")).status());
        assertEquals(PortalStatus.OPEN_FRAME, scan(plane(
                "#",
                "#",
                "C")).status());
    }

    @Test
    void noFrameAndTwoCores() {
        assertEquals(PortalStatus.NO_FRAME, scan(plane("C")).status());
        assertEquals(PortalStatus.TWO_CORES, scan(plane(
                "#####",
                "#...#",
                "#C.C#",
                "#####")).status());
    }

    @Test
    void somethingInTheOpeningBlocksIt() {
        assertEquals(PortalStatus.BLOCKED, scan(plane(
                "#####",
                "#.X.#",
                "#...#",
                "##C##")).status());
    }

    @Test
    void frameOutOfThePlaneIsNotFlat() {
        Map<Pos, Cell> world = plane(
                "#####",
                "#...#",
                "##C##");
        world.put(new Pos(0, 2, 1), Cell.FRAME);
        assertEquals(PortalStatus.NOT_FLAT, scan(world).status());
    }

    @Test
    void coreMayTouchTheRingFromOutsideThePlane() {
        Map<Pos, Cell> world = plane(
                "#####",
                "#...#",
                "#####");
        world.put(new Pos(0, 1, 1), Cell.CORE);
        PortalShape.Result r = scan(world);
        assertEquals(PortalStatus.NO_DESTINATION, r.status());
        assertEquals(3, r.opening().size());
        assertEquals(12, r.frame().size());
        assertTrue(r.touches(new Pos(2, 1, 0)));
    }

    @Test
    void sidewaysPlaneUsesTheZAxis() {
        Map<Pos, Cell> world = new HashMap<>();
        for (int z = 0; z < 4; z++) {
            for (int y = 0; y < 4; y++) {
                boolean edge = z == 0 || z == 3 || y == 0 || y == 3;
                if (edge) {
                    world.put(new Pos(5, y, z), (z == 1 && y == 0) ? Cell.CORE : Cell.FRAME);
                }
            }
        }
        PortalShape.Result r = scan(world);
        assertEquals(PortalShape.Axis.Z, r.axis());
        assertEquals(4, r.opening().size());
        assertTrue(r.opening().contains(new Pos(5, 1, 1)));
    }

    @Test
    void tooBigIsRefused() {
        StringBuilder top = new StringBuilder(), mid = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            top.append('#');
            mid.append(i == 0 || i == 29 ? '#' : '.');
        }
        String bottom = "C" + top.substring(1);
        assertEquals(PortalStatus.TOO_BIG, scan(plane(top.toString(), mid.toString(), bottom)).status());
        Map<Pos, Cell> small = plane(
                "#####",
                "#...#",
                "#...#",
                "##C##");
        assertEquals(PortalStatus.TOO_BIG, PortalShape.scan((x, y, z) -> small.getOrDefault(new Pos(x, y, z), Cell.AIR), core(small), MAX, 4).status());
    }
}
