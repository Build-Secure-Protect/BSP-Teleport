package com.mrgregles.bsp_teleport;

import com.mrgregles.bsp_teleport.data.PortalRow;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.portal.PortalDestination;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The portal destination's table form and the row id. */
class PortalWireTest {
    @Test
    void destinationRoundTrips() {
        PortalDestination link = PortalDestination.link("hub-east");
        assertEquals(link, PortalDestination.parse(link.serialize()));
        PortalDestination warp = PortalDestination.warp("arena", true);
        assertEquals(warp, PortalDestination.parse(warp.serialize()));
        PortalDestination random = PortalDestination.random("");
        assertEquals(random, PortalDestination.parse(random.serialize()));
        PortalDestination position = PortalDestination.position(new Position("BSP-Spawn", "minecraft:overworld", 1.5, 64, -2.5, 90f, 0f));
        assertEquals(position, PortalDestination.parse(position.serialize()));
        assertEquals("position 1, 64, -3 (minecraft:overworld on BSP-Spawn)", position.describe());
    }

    @Test
    void badTextIsNoDestination() {
        assertEquals(PortalDestination.NONE, PortalDestination.parse(""));
        assertEquals(PortalDestination.NONE, PortalDestination.parse("SOMETHING|x|"));
        assertFalse(PortalDestination.parse("garbage").isSet());
        assertNull(PortalDestination.parse("LINK|hub|").position());
    }

    @Test
    void rowIdNamesServerWorldAndPlace() {
        assertEquals("BSP-Spawn|minecraft:overworld|10,64,-3", PortalRow.idOf("BSP-Spawn", "minecraft:overworld", 10, 64, -3));
    }
}
