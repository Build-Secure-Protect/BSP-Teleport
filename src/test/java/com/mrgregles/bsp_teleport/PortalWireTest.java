package com.mrgregles.bsp_teleport;

import com.mrgregles.bsp_teleport.data.PortalRow;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.portal.PortalDestination;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    /** D110, D116: Back carries no target, keeps its label through the wire form, reads "back", and a portal holds one at most. */
    @Test
    void a_back_entry_round_trips_and_is_one_place() {
        PortalDestination back = PortalDestination.back();
        assertEquals(back, PortalDestination.parse(back.serialize()));
        PortalDestination labelled = back.withLabel("Home again");
        assertEquals("Home again", PortalDestination.parse(labelled.serialize()).label());
        assertTrue(PortalDestination.parse(labelled.serialize()).isBack());
        assertEquals("back", back.describe());
        assertTrue(back.samePlace(labelled));
        assertFalse(back.samePlace(PortalDestination.random("")));
        assertEquals(2, PortalDestination.parseAll(PortalDestination.serializeAll(java.util.List.of(back, PortalDestination.warp("market", false)))).size());
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

    @Test
    void a_destination_list_keeps_labels_and_reads_old_single_rows() {
        var market = PortalDestination.warp("market", false).withLabel("Market");
        var arena = PortalDestination.portal("BSP-SafeRoom|minecraft:overworld|4,65,-90");
        var text = PortalDestination.serializeAll(java.util.List.of(market, arena));
        var back = PortalDestination.parseAll(text);
        assertEquals(2, back.size());
        assertEquals("Market", back.get(0).label());
        assertEquals("market", back.get(0).labelOr("x").equals("Market") ? "market" : "?");
        assertEquals(PortalDestination.Kind.PORTAL, back.get(1).kind());
        assertEquals("", back.get(1).label());
        assertEquals("Arena Door", back.get(1).labelOr("Arena Door"));
        // a row written before labels existed has three fields
        var old = PortalDestination.parse("WARP|shop|");
        assertEquals(PortalDestination.Kind.WARP, old.kind());
        assertEquals("shop", old.target());
        assertEquals("", old.label());
        assertTrue(market.samePlace(PortalDestination.warp("MARKET", false)));
        assertEquals(0, PortalDestination.parseAll("").size());
    }

    @Test
    void a_portal_with_several_destinations_is_summed_up_by_its_labels() {
        var market = PortalDestination.warp("market", false).withLabel("Market");
        var arena = PortalDestination.portal("BSP-SafeRoom|minecraft:overworld|4,65,-90").withLabel("Arena Door");
        var mines = PortalDestination.warp("mines", true).withLabel("Old Mines");
        assertEquals("-", com.mrgregles.bsp_teleport.portal.Portals.summary(java.util.List.of()));
        assertEquals("warp market", com.mrgregles.bsp_teleport.portal.Portals.summary(java.util.List.of(market)));
        assertEquals("2 destinations: Market, Arena Door", com.mrgregles.bsp_teleport.portal.Portals.summary(java.util.List.of(market, arena)));
        assertEquals("3 destinations: Market, Arena Door, +1", com.mrgregles.bsp_teleport.portal.Portals.summary(java.util.List.of(market, arena, mines)));
    }
}
