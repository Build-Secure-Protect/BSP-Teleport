package com.mrgregles.bsp_teleport.teleport;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T83: random spots are drawn inside both the ring and the border, so no try is spent outside the border. */
class RandomTeleportTest {
    private static void assertInside(int[] p, double[] box, double cx, double cz, double min, double max) {
        assertNotNull(p);
        double d = Math.hypot(p[0] - cx, p[1] - cz);
        assertTrue(p[0] >= box[0] && p[0] <= box[2] && p[1] >= box[1] && p[1] <= box[3], "inside the border: " + p[0] + "," + p[1]);
        assertTrue(d >= min && d <= max, "inside the ring: " + d);
    }

    @Test
    void a_huge_ring_round_a_small_border_still_finds_spots_every_time() {
        // A 2000 border (shrunk by 16) with maxRadius at a million: before T83 nearly every try fell outside the border.
        double[] box = {-984, -984, 984, 984};
        Random random = new Random(1);
        for (int i = 0; i < 1000; i++) {
            assertInside(RandomTeleport.pick(random, box, 0, 0, 500, 1_000_000), box, 0, 0, 500, 1_000_000);
        }
    }

    @Test
    void a_small_ring_inside_a_big_border_draws_from_the_ring() {
        double[] box = {-29_000_000, -29_000_000, 29_000_000, 29_000_000};
        Random random = new Random(2);
        for (int i = 0; i < 1000; i++) {
            assertInside(RandomTeleport.pick(random, box, 100, -50, 500, 5000), box, 100, -50, 500, 5000);
        }
    }

    @Test
    void spawn_near_an_edge_uses_only_the_part_of_the_ring_inside() {
        double[] box = {-484, -484, 484, 484};
        Random random = new Random(3);
        for (int i = 0; i < 1000; i++) {
            assertInside(RandomTeleport.pick(random, box, 450, 450, 200, 900), box, 450, 450, 200, 900);
        }
    }

    @Test
    void a_ring_that_misses_the_border_draws_anywhere_inside_it() {
        double[] small = {-134, -134, 134, 134};
        Random random = new Random(4);
        for (int i = 0; i < 200; i++) {
            int[] p = RandomTeleport.pick(random, small, 0, 0, 500, 5000);
            assertTrue(p[0] >= -134 && p[0] <= 134 && p[1] >= -134 && p[1] <= 134);
        }
    }

    @Test
    void borders_that_do_not_overlap_give_no_spot() {
        double[] empty = {500, 500, 400, 600};
        org.junit.jupiter.api.Assertions.assertNull(RandomTeleport.pick(new Random(5), empty, 0, 0, 100, 5000));
    }

    @Test
    void use_border_reaches_the_farthest_corner() {
        double[] box = {-984, -984, 984, 984};
        assertEquals(Math.hypot(984, 984), RandomTeleport.farthest(box, 0, 0), 1e-9);
        assertEquals(Math.hypot(1968, 1968), RandomTeleport.farthest(box, 984, 984), 1e-9);
    }
}
