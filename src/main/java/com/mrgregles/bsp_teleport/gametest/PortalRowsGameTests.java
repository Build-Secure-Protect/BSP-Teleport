package com.mrgregles.bsp_teleport.gametest;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.data.PortalRow;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.portal.Portals;
import com.mrgregles.bsp_teleport.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** D146 (B18): a server keeps only the rows of its name that have a Portal Core at their spot, and leaves other servers' rows alone. */
@GameTestHolder(BSPTeleport.MODID)
@PrefixGameTestTemplate(false)
public class PortalRowsGameTests {
    private static PortalRow row(String server, String world, BlockPos p, String name) {
        return new PortalRow(PortalRow.idOf(server, world, p.getX(), p.getY(), p.getZ()), server, world, p.getX(), p.getY(), p.getZ(), name,
                List.of(), false, null, false, "", "READY", "", 1L, 0);
    }

    @GameTest(template = "empty")
    public static void rows_without_a_core_here_are_removed_and_the_rest_kept(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        TeleportData data = TeleportData.get(server);
        String world = h.getLevel().dimension().location().toString();
        String here = TeleportConfig.serverName();
        BlockPos empty = h.absolutePos(new BlockPos(3, 1, 3));
        BlockPos withCore = h.absolutePos(new BlockPos(1, 1, 1));
        h.getLevel().setBlock(withCore, ModBlocks.PORTAL_CORE.get().defaultBlockState(), 3);
        PortalRow stale = row(here, world, empty, "Stale");
        PortalRow live = row(here, world, withCore, "Live");
        PortalRow elsewhere = row("BSP-Elsewhere", world, empty, "Elsewhere");
        PortalRow noWorld = row(here, "bsp_teleport:no_such_world", empty, "Lost");
        try {
            for (PortalRow r : List.of(stale, live, elsewhere, noWorld)) {
                data.setPortal(r);
            }
            Portals.checkOwnRows(server, List.of(stale, live, elsewhere, noWorld));
            List<String> left = data.portals().stream().map(PortalRow::id).toList();
            if (left.contains(stale.id()) || left.contains(noWorld.id())) {
                h.fail("rows of this server with no core (or no world) should be gone, left " + left);
                return;
            }
            if (!left.contains(elsewhere.id())) {
                h.fail("another server's row must be left alone");
                return;
            }
            if (data.portals().stream().noneMatch(r -> r.id().equals(live.id()) || r.id().startsWith(here + "|" + world + "|" + withCore.getX()))) {
                h.fail("the row of a real core must stay");
                return;
            }
            h.succeed();
        } finally {
            for (PortalRow r : List.of(stale, live, elsewhere, noWorld)) {
                data.deletePortal(r.id());
            }
        }
    }
}
