package com.mrgregles.bsp_teleport;

import com.mrgregles.bsp_teleport.api.Destination;
import com.mrgregles.bsp_teleport.teleport.PendingLandings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B7: a stored landing is applied only on the server it names. */
class PendingLandingsTest {
    private static Destination to(String server) {
        return new Destination(server, "minecraft:overworld", 10, 64, 10, "");
    }

    @Test
    void a_landing_is_only_for_the_server_it_names() {
        assertTrue(PendingLandings.forThisServer(to("BSP-Wilds"), "BSP-Wilds"));
        assertTrue(PendingLandings.forThisServer(to("bsp-wilds"), "BSP-Wilds"));
        assertFalse(PendingLandings.forThisServer(to("BSP-Wilds"), "BSP-Spawn"));
    }

    @Test
    void a_landing_without_a_server_counts_as_local() {
        assertTrue(PendingLandings.forThisServer(to(""), "BSP-Spawn"));
    }
}
