package com.mrgregles.bsp_teleport.gametest;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.compat.BspCore;
import com.mrgregles.bsp_teleport.team.AllyTeleports;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * A-1: players in no alliance are never asked or moved, and the BSP-Core 1.10.0 alliance calls answer at runtime.
 * BSP-Core's API cannot make an alliance, so the ask, the accept and the cancels are the owner's in-game check.
 */
@GameTestHolder(BSPTeleport.MODID)
@PrefixGameTestTemplate(false)
public class AllyTeleportGameTests {
    @GameTest(template = "empty")
    public static void players_in_no_alliance_are_not_asked(GameTestHelper h) {
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        UUID other = UUID.randomUUID();
        h.assertTrue(!BspCore.allied(player.server, player.getUUID(), other), "two players in no team are not allies");
        h.assertTrue(BspCore.allies(player.server, player.getUUID()).isEmpty(), "a player in no team has no allies");
        h.assertTrue(BspCore.allianceName(player.server, player.getUUID()).isEmpty(), "and no alliance");
        h.assertTrue(!BspCore.isStealing(player.server, player.getUUID()), "and is stealing nothing");
        h.assertTrue("ally.ended".equals(AllyTeleports.refusal(player.server, player.getUUID(), other)), "a move between non-allies is refused");
        h.assertTrue(!AllyTeleports.ask(player, other), "a non-ally is not asked");
        h.assertTrue(!AllyTeleports.ask(player, player.getUUID()), "nor is the player themselves");
        h.succeed();
    }
}
