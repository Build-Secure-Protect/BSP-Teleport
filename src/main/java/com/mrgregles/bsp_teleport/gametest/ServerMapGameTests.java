package com.mrgregles.bsp_teleport.gametest;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.data.PortalRow;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.data.Warp;
import com.mrgregles.bsp_teleport.map.MapData;
import com.mrgregles.bsp_teleport.map.MapLayout;
import com.mrgregles.bsp_teleport.map.MapLayout.Link;
import com.mrgregles.bsp_teleport.map.MapLayout.Side;
import com.mrgregles.bsp_teleport.map.MapService;
import com.mrgregles.bsp_teleport.portal.PortalDestination;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * D201: the band's MAP tab as the server builds it. Four worlds wrap round on every side; a server with a warp floats
 * off the grid; a portal on a grid world leads to another floating server; a hidden server is left out.
 */
@GameTestHolder(BSPTeleport.MODID)
@PrefixGameTestTemplate(false)
public class ServerMapGameTests {
    private static final String EMPTY = "empty";

    private static Map<Side, Link> links(Object... pairs) {
        Map<Side, Link> out = new EnumMap<>(Side.class);
        for (int i = 0; i < pairs.length; i += 3) {
            out.put((Side) pairs[i], new Link((String) pairs[i + 1], (Side) pairs[i + 2]));
        }
        return out;
    }

    private static PortalRow portal(String server, PortalDestination to) {
        return new PortalRow(PortalRow.idOf(server, "minecraft:overworld", 1, 64, 1), server, "minecraft:overworld", 1, 64, 1, "Event Gate",
                List.of(to), false, null, true, "", "", "", 0L, 0);
    }

    @GameTest(template = EMPTY)
    public static void the_map_shows_the_grid_the_floating_servers_and_no_hidden_ones(GameTestHelper h) {
        TeleportData data = TeleportData.get(h.getLevel().getServer());
        List<? extends String> hiddenBefore = TeleportConfig.MAP_HIDDEN_SERVERS.get();
        String firstBefore = TeleportConfig.MAP_FIRST_SERVER.get();
        String warp = "tp_map_bank", hiddenWarp = "tp_map_hidden";
        try {
            TeleportConfig.MAP_HIDDEN_SERVERS.set(List.of("M-Hidden"));
            TeleportConfig.MAP_FIRST_SERVER.set("M-Frost");
            data.setWarp(new Warp(warp, new Position("M-Spawn", "minecraft:overworld", 10, 70, 10, 0f, 0f), 0, false));
            data.setWarp(new Warp(hiddenWarp, new Position("M-Hidden", "minecraft:overworld", 10, 70, 10, 0f, 0f), 0, false));
            List<MapLayout.World> worlds = List.of(
                    new MapLayout.World("M-Frost", 8000, links(Side.EAST, "M-Boulder", Side.WEST, Side.SOUTH, "M-Moon", Side.NORTH,
                            Side.NORTH, "M-Moon", Side.SOUTH, Side.WEST, "M-Boulder", Side.EAST)),
                    new MapLayout.World("M-Boulder", 8000, links(Side.SOUTH, "M-Storm", Side.NORTH, Side.NORTH, "M-Storm", Side.SOUTH)),
                    new MapLayout.World("M-Moon", 6000, links(Side.EAST, "M-Storm", Side.WEST, Side.WEST, "M-Storm", Side.EAST)),
                    new MapLayout.World("M-Storm", 10000, Map.of()));
            List<PortalRow> rows = List.of(portal("M-Storm", new PortalDestination(PortalDestination.Kind.RANDOM, "M-Events", null)));

            ServerPlayer player = TestPlayers.mockServerPlayer(h);
            MapData map = MapService.build(player, worlds, rows);
            h.assertTrue(map.cols() == 2 && map.rows() == 2, "a 2 by 2 grid: " + map.cols() + " x " + map.rows());
            MapData.Server frost = map.server("M-Frost");
            h.assertTrue(frost != null && frost.kind() == MapData.Kind.GRID && frost.x() == 0 && frost.y() == 0 && frost.size() == 8000,
                    "Frost is top left, 8000 across: " + frost);
            h.assertTrue(frost.crossings().size() == 4, "with a crossing on every side: " + frost.crossings());
            MapData.Server storm = map.server("M-Storm");
            h.assertTrue(storm != null && storm.x() == 1 && storm.y() == 1 && storm.size() == 10000, "Storm bottom right: " + storm);
            h.assertTrue(map.bridges().size() == 4 && map.stubs().size() == 8, "4 bridges and 8 far-side halves: " + map.bridges().size() + " " + map.stubs().size());

            MapData.Server spawn = map.server("M-Spawn");
            h.assertTrue(spawn != null && spawn.kind() == MapData.Kind.OFF && spawn.warps().contains(warp), "the warp's server floats with its warp: " + spawn);
            MapData.Server events = map.server("M-Events");
            h.assertTrue(events != null && events.kind() == MapData.Kind.OFF, "the portal's target floats: " + events);
            h.assertTrue(map.portals().contains(new MapData.Portal("M-Storm", "M-Events")), "with a portal line from Storm: " + map.portals());
            h.assertTrue(map.server("M-Hidden") == null, "a hidden server is left out though a warp is there");

            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            map.encode(buf);
            MapData back = MapData.decode(buf);
            h.assertTrue(back.equals(map) && buf.readableBytes() == 0, "the packet reads back the same");
        } finally {
            TeleportConfig.MAP_HIDDEN_SERVERS.set(hiddenBefore);
            TeleportConfig.MAP_FIRST_SERVER.set(firstBefore);
            data.deleteWarp(warp);
            data.deleteWarp(hiddenWarp);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void without_region_guards_links_every_server_is_off_the_grid(GameTestHelper h) {
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        MapData map = MapService.build(player, List.of(), List.of());
        h.assertTrue(map.cols() == 0 && map.bridges().isEmpty() && map.stubs().isEmpty(), "no grid");
        MapData.Server here = map.server(TeleportConfig.serverName());
        h.assertTrue(here != null && here.kind() != MapData.Kind.GRID && here.up(), "this server shows, up, off the grid: " + here);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void this_server_spelt_in_another_case_on_the_grid_is_still_here_and_up(GameTestHelper h) {
        // Audit: Region Guard's name for this server differing only in case must not make it a second, offline server.
        String upper = TeleportConfig.serverName().toUpperCase(java.util.Locale.ROOT);
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        MapData map = MapService.build(player, List.of(new MapLayout.World(upper, 4000, links(Side.EAST, "M-Next", Side.WEST))), List.of());
        MapData.Server here = map.server(upper);
        h.assertTrue(map.here().equals(upper), "the map calls this server by the grid's spelling: " + map.here());
        h.assertTrue(here != null && here.kind() == MapData.Kind.GRID && here.up(), "on the grid and up: " + here);
        h.assertTrue(map.servers().stream().filter(x -> x.name().equalsIgnoreCase(upper)).count() == 1, "once");
        h.succeed();
    }
}
