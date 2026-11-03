package com.mrgregles.bsp_teleport.gametest;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.data.Home;
import com.mrgregles.bsp_teleport.data.HomeLimits;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.teleport.TeleportActions;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * B40: Set home never moves a home in use without being asked, and with every slot used a new home only comes in by
 * replacing an unlocked one, in its place in the list. The band's footer question is the owner's in-game check.
 */
@GameTestHolder(BSPTeleport.MODID)
@PrefixGameTestTemplate(false)
public class HomeOverwriteGameTests {
    @GameTest(template = "empty")
    public static void set_home_asks_before_moving_or_replacing_a_home(GameTestHelper h) {
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        TeleportData data = TeleportData.get(player.server);
        int slots = HomeLimits.slots(player);
        try {
            player.setPos(10.5, player.getY(), 10.5);
            h.assertTrue(TeleportActions.setHome(player, "base"), "a new name with a free slot is set");
            player.setPos(30.5, player.getY(), 30.5);
            h.assertTrue(!TeleportActions.setHome(player, "BASE"), "a name in use only asks");
            h.assertTrue(data.home(player.getUUID(), "base").position().x() == 10.5, "asking leaves the home where it was");
            h.assertTrue(TeleportActions.setHome(player, "base", true), "the answer moves it");
            h.assertTrue(data.home(player.getUUID(), "base").position().x() == 30.5, "the moved home is here");

            for (int i = data.homesOf(player.getUUID()).size(); i < slots; i++) {
                h.assertTrue(TeleportActions.setHome(player, "h" + i), "filling slot " + i);
            }
            h.assertTrue(!TeleportActions.setHome(player, "tower"), "a new name with every slot used only asks");
            h.assertTrue(data.homesOf(player.getUUID()).size() == slots, "asking adds no home");

            List<Home> before = data.homesOf(player.getUUID());
            String replaced = before.get(before.size() - 1).name();
            h.assertTrue(!TeleportActions.replaceHome(player, replaced, "base"), "a replacement may not take a name in use");
            player.setPos(50.5, player.getY(), 50.5);
            h.assertTrue(TeleportActions.replaceHome(player, replaced, "tower"), "the answer replaces the picked home");
            List<Home> after = data.homesOf(player.getUUID());
            h.assertTrue(after.size() == slots && data.home(player.getUUID(), replaced) == null, "the picked home is gone and no slot is added: " + after);
            h.assertTrue(data.homeIndex(player.getUUID(), "tower") == before.size() - 1, "the new home takes the picked one's place: " + after);
            h.assertTrue(data.home(player.getUUID(), "tower").position().x() == 50.5, "the new home is here");
            h.assertTrue(data.home(player.getUUID(), "base").position().x() == 30.5, "the other homes are untouched");
            h.succeed();
        } finally {
            data.forgetHomes(player.getUUID());
        }
    }
}
