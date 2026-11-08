package com.mrgregles.bsp_teleport.map;

import com.mrgregles.bsp_teleport.map.MapLayout.Cell;
import com.mrgregles.bsp_teleport.map.MapLayout.Link;
import com.mrgregles.bsp_teleport.map.MapLayout.Result;
import com.mrgregles.bsp_teleport.map.MapLayout.Side;
import com.mrgregles.bsp_teleport.map.MapLayout.Stub;
import com.mrgregles.bsp_teleport.map.MapLayout.World;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D201: the border grid of the band's MAP tab. */
class MapLayoutTest {
    private final Map<String, Map<Side, Link>> links = new HashMap<>();
    private final Map<String, Integer> sizes = new HashMap<>();

    /** A link made on {@code a}'s server: a's side to b's side. */
    private void link(String a, Side as, String b, Side bs) {
        links.computeIfAbsent(a, k -> new EnumMap<>(Side.class)).put(as, new Link(b, bs));
    }

    private List<World> worlds() {
        List<World> out = new ArrayList<>();
        links.forEach((s, l) -> out.add(new World(s, sizes.getOrDefault(s, 0), l)));
        sizes.forEach((s, size) -> {
            if (!links.containsKey(s)) {
                out.add(new World(s, size, Map.of()));
            }
        });
        return out;
    }

    private static Cell cell(Result r, String server) {
        return r.cells().stream().filter(c -> c.server().equals(server)).findFirst().orElseThrow(() -> new AssertionError(server + " not placed"));
    }

    private static void at(Result r, String server, int x, int y) {
        Cell c = cell(r, server);
        assertEquals(x + "," + y, c.x() + "," + c.y(), server);
    }

    @Test
    void theFourWorldsWrapRoundOnEverySide() {
        sizes.putAll(Map.of("BSP-Frostwarf", 8000, "BSP-Boulderbrook", 8000, "BSP-Moongrove", 6000, "BSP-Stormfall", 10000));
        link("BSP-Frostwarf", Side.EAST, "BSP-Boulderbrook", Side.WEST);
        link("BSP-Moongrove", Side.EAST, "BSP-Stormfall", Side.WEST);
        link("BSP-Frostwarf", Side.SOUTH, "BSP-Moongrove", Side.NORTH);
        link("BSP-Stormfall", Side.NORTH, "BSP-Boulderbrook", Side.SOUTH); // made on the other server: still two-way
        link("BSP-Frostwarf", Side.NORTH, "BSP-Moongrove", Side.SOUTH);
        link("BSP-Boulderbrook", Side.NORTH, "BSP-Stormfall", Side.SOUTH);
        link("BSP-Frostwarf", Side.WEST, "BSP-Boulderbrook", Side.EAST);
        link("BSP-Moongrove", Side.WEST, "BSP-Stormfall", Side.EAST);

        Result r = MapLayout.layout(worlds(), "BSP-Frostwarf");
        at(r, "BSP-Frostwarf", 0, 0);
        at(r, "BSP-Boulderbrook", 1, 0);
        at(r, "BSP-Moongrove", 0, 1);
        at(r, "BSP-Stormfall", 1, 1);
        assertEquals(2, r.cols());
        assertEquals(2, r.rows());
        assertEquals(4, r.bridges().size(), "the four shared borders");
        assertEquals(8, r.stubs().size(), "a far-side half tile at both ends of the other four");
        assertTrue(r.stubs().contains(new Stub("BSP-Moongrove", Side.SOUTH, "BSP-Frostwarf")), "Moongrove's south leads to Frostwarf");
        assertTrue(r.stubs().contains(new Stub("BSP-Boulderbrook", Side.EAST, "BSP-Frostwarf")), "Boulderbrook's east leads to Frostwarf");
        assertEquals(10000, cell(r, "BSP-Stormfall").size());
        assertEquals(4, r.crossings().get("BSP-Moongrove").size());
    }

    @Test
    void withoutAFirstServerItStartsFromTheFirstName() {
        link("Beta", Side.WEST, "Alpha", Side.EAST);
        Result r = MapLayout.layout(worlds());
        at(r, "Alpha", 0, 0);
        at(r, "Beta", 1, 0);
        assertEquals(1, r.bridges().size());
        assertTrue(r.stubs().isEmpty());
    }

    @Test
    void aLinkGoingWestOrNorthStillPlacesTheGridFromZero() {
        link("Alpha", Side.NORTH, "Beta", Side.SOUTH);
        link("Alpha", Side.WEST, "Gamma", Side.EAST);
        Result r = MapLayout.layout(worlds());
        at(r, "Gamma", 0, 1);
        at(r, "Alpha", 1, 1);
        at(r, "Beta", 1, 0);
        assertEquals(2, r.cols());
        assertEquals(2, r.rows());
    }

    @Test
    void aTakenCellBecomesAStubAtBothEnds() {
        // Alpha east is Beta, Alpha south is Gamma. Beta south places Epsilon under Beta first, so Gamma's east link to
        // Delta finds that cell taken: a stub at both ends, and Delta gets a tile of its own in a group of its own.
        link("Alpha", Side.EAST, "Beta", Side.WEST);
        link("Alpha", Side.SOUTH, "Gamma", Side.NORTH);
        link("Gamma", Side.EAST, "Delta", Side.WEST);
        link("Beta", Side.SOUTH, "Epsilon", Side.NORTH);
        Result r = MapLayout.layout(worlds());
        at(r, "Epsilon", 1, 1);
        assertTrue(r.stubs().contains(new Stub("Gamma", Side.EAST, "Delta")));
        assertTrue(r.stubs().contains(new Stub("Delta", Side.WEST, "Gamma")));
        at(r, "Delta", 3, 0);
        assertEquals(4, r.cols());
    }

    @Test
    void separateGroupsSitSideBySideAndUnlinkedWorldsAreLeftOut() {
        link("Alpha", Side.EAST, "Beta", Side.WEST);
        link("Gamma", Side.SOUTH, "Delta", Side.NORTH);
        sizes.put("Lonely", 2000);
        Result r = MapLayout.layout(worlds());
        at(r, "Alpha", 0, 0);
        at(r, "Beta", 1, 0);
        at(r, "Gamma", 3, 0);
        at(r, "Delta", 3, 1);
        assertEquals(4, r.cols());
        assertEquals(2, r.rows());
        assertTrue(r.cells().stream().noneMatch(c -> c.server().equals("Lonely")));
    }

    @Test
    void aLinkRecordedAtBothEndsCountsOnce() {
        link("Alpha", Side.EAST, "Beta", Side.WEST);
        link("Beta", Side.WEST, "Alpha", Side.EAST);
        Result r = MapLayout.layout(worlds());
        assertEquals(1, r.bridges().size());
        assertTrue(r.stubs().isEmpty());
        assertTrue(MapLayout.layout(List.of()).isEmpty());
    }

    @Test
    void regionGuardsEdgeWorldsAreTakenForTheOverworldOnly() {
        // TR6: a link from A's east to B's west shows only on A; B's own row lists none of it.
        World a = MapLayout.fromEdges("BSP-A", "minecraft:overworld", 8000, Map.of(
                "east", new String[] {"BSP-B", "minecraft:overworld", "west"},
                "north", new String[] {"BSP-C", "minecraft:the_nether", "south"},
                "up", new String[] {"BSP-D", "minecraft:overworld", "west"},
                "south", new String[] {"BSP-E", "minecraft:overworld", "sideways"}));
        assertEquals(8000, a.size());
        assertEquals(Map.of(Side.EAST, new Link("BSP-B", Side.WEST)), a.links(), "only the overworld link with real sides");
        World b = MapLayout.fromEdges("BSP-B", "minecraft:overworld", 0, Map.of());
        assertEquals(0, b.size(), "links but no border: size 0");
        assertTrue(MapLayout.fromEdges("BSP-A", "minecraft:the_nether", 3000, Map.of()) == null, "another dimension is left out");

        Result r = MapLayout.layout(List.of(a, b));
        at(r, "BSP-A", 0, 0);
        at(r, "BSP-B", 1, 0);
        assertEquals(1, r.bridges().size(), "joined from the one end that has the link");
        assertEquals(1, r.crossings().get("BSP-B").size(), "and B knows its west leads to A");
    }
}
