package com.mrgregles.bsp_teleport.gametest;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.api.Destination;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.teleport.PendingLandings;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * B50: where a cross-server landing near the player's raided totem goes. The live steal check needs BSP-Core and a
 * real steal, so the raid is a stand-in here; the choice of spot is what is tested.
 */
@GameTestHolder(BSPTeleport.MODID)
@PrefixGameTestTemplate(false)
public class RaidLandingGameTests {
    @GameTest(template = "empty")
    public static void a_landing_near_a_raid_goes_to_a_spot_on_this_server(GameTestHelper h) {
        String here = TeleportConfig.serverName();
        Destination world = PendingLandings.worldSpawn(h.getLevel().getServer());
        Position own = new Position(here, "minecraft:overworld", -20.5, 71, 5.5, 0f, 0f);
        Position elsewhere = new Position("BSP-Elsewhere", "minecraft:overworld", 0.5, 64, 0.5, 0f, 0f);

        Destination d = PendingLandings.raidFallback(own, world, x -> false, 3);
        if (d.x() != -20.5 || d.z() != 5.5 || d.crossServer() && !d.server().equalsIgnoreCase(here) || d.protect() != 3) {
            h.fail("this server's own spot, clear of the raid, with the landing's protection: " + d);
            return;
        }
        d = PendingLandings.raidFallback(elsewhere, world, x -> false, 3);
        if (d.x() != world.x() || d.z() != world.z() || d.crossServer()) {
            h.fail("a spawn on another server is never used: the world spawn here instead (B50): " + d);
            return;
        }
        d = PendingLandings.raidFallback(null, world, x -> false, -1);
        if (d.x() != world.x() || d.z() != world.z() || d.protect() != -1) {
            h.fail("no spawn spot at all: the world spawn: " + d);
            return;
        }
        d = PendingLandings.raidFallback(own, world, x -> x.x() == -20.5, 3);
        if (d.x() != world.x() || d.z() != world.z()) {
            h.fail("this server's spot is inside the raid too: the world spawn: " + d);
            return;
        }
        h.succeed();
    }
}
