package com.mrgregles.bsp_teleport.teleport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code /tpall to <server> <x y z> [world]}: the short world names an admin types. */
class WorldIdTest {
    @Test
    void no_world_is_the_overworld() {
        assertEquals("minecraft:overworld", AdminActions.worldId(null));
        assertEquals("minecraft:overworld", AdminActions.worldId(" "));
    }

    @Test
    void short_names_become_world_ids() {
        assertEquals("minecraft:the_nether", AdminActions.worldId("Nether"));
        assertEquals("minecraft:the_end", AdminActions.worldId("end"));
        assertEquals("minecraft:overworld", AdminActions.worldId("overworld"));
        assertEquals("minecraft:the_nether", AdminActions.worldId("the_nether"));
    }

    @Test
    void full_ids_are_kept() {
        assertEquals("bsp_core:mining", AdminActions.worldId("bsp_core:mining"));
        assertEquals("minecraft:arena", AdminActions.worldId("arena"));
    }
}
