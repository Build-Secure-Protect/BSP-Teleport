package com.mrgregles.bsp_teleport.gametest;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.api.Destination;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.teleport.TeleportService;
import com.mrgregles.bsp_teleport.teleport.Teleports;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests for B31: a move the server makes on its own (the respawn fix, a first placement, an access bounce) leaves
 * the /back spot alone, so the band's Back still goes to the death spot; a move the player asked for still records it.
 */
@GameTestHolder(BSPTeleport.MODID)
@PrefixGameTestTemplate(false)
public class BackSpotGameTests {
    private static final String EMPTY = "empty";

    @GameTest(template = EMPTY)
    public static void a_server_placement_keeps_the_back_spot_and_a_teleport_records_it(GameTestHelper h) {
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        String world = h.getLevel().dimension().location().toString();
        Position death = new Position(TeleportConfig.serverName(), world, 1189, 78, -1474, 0, 0);
        Teleports.restore(player.getUUID(), 0L, death);

        TeleportService.INSTANCE.place(player, new Destination("", world, 20.5, 90, 20.5, ""));
        Position kept = Teleports.back(player);
        h.assertTrue(kept != null && kept.x() == 1189 && kept.z() == -1474, "a placement keeps the death spot: " + kept);

        TeleportService.INSTANCE.teleport(player, new Destination("", world, 40.5, 90, 40.5, ""));
        Position recorded = Teleports.back(player);
        h.assertTrue(recorded != null && Math.abs(recorded.x() - 20.5) < 1 && Math.abs(recorded.z() - 20.5) < 1,
                "a teleport records where the player stood: " + recorded);
        h.succeed();
    }
}
