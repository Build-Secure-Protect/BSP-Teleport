package com.mrgregles.bsp_teleport.gametest;

import com.mrgregles.bsp_core.api.BspTotems;
import com.mrgregles.bsp_core.totem.TotemUpgrades;
import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.compat.BspCore;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * B43: Extra Homes and Swift Travel come from BSP-Core's {@code BspTotems.highestLevel}, which from BSP-Core 1.10.1 on
 * also counts the player's totems placed on the network's other servers. A headless server has no other servers and
 * no totem, so this checks the base answers and that both 1.10.1 forms link; the hub reading a home server's totem is
 * the owner's live check (TESTING "Totem upgrades on the spawn hub").
 */
@GameTestHolder(BSPTeleport.MODID)
@PrefixGameTestTemplate(false)
public class TotemLevelGameTests {
    @GameTest(template = "empty")
    public static void a_player_with_no_totem_gets_the_base_homes_and_cooldown(GameTestHelper h) {
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        h.assertTrue(BspCore.extraHomeSlots(player) == 0, "no totem, no extra home slots");
        h.assertTrue(BspCore.swiftTravelLevel(player) == 0, "no totem, no Swift Travel");
        h.assertTrue(BspCore.cooldownSeconds(player, 60) == 60, "no totem, the full cooldown");
        h.assertTrue(BspTotems.highestLevel(player.server, player.getUUID(), TotemUpgrades.Buff.EXTRA_HOMES) == 0,
                "the server and UUID form answers the same");
        h.succeed();
    }
}
