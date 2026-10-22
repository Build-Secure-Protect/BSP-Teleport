package com.mrgregles.bsp_teleport.gametest;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.compat.BspCore;
import com.mrgregles.bsp_teleport.compat.LuckPerms;
import com.mrgregles.bsp_teleport.perm.Permissions;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * T70, D109: the dev run has neither LuckPerms nor Bukkit, so a node gets no answer from either and BSP-Core's admin
 * list stands in. The LuckPerms route itself needs the LuckPerms Forge mod and is the owner's in-game check.
 */
@GameTestHolder(BSPTeleport.MODID)
@PrefixGameTestTemplate(false)
public class PermissionsGameTests {
    @GameTest(template = "empty")
    public static void without_luckperms_or_bukkit_nodes_fall_back_to_the_admin_list(GameTestHelper h) {
        if (LuckPerms.isPresent()) {
            h.fail("this test expects a dev run without the LuckPerms Forge mod");
            return;
        }
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        if (LuckPerms.check(player, Permissions.BYPASS_WARMUP).isPresent() || Permissions.node(player, Permissions.BYPASS_WARMUP).isPresent()) {
            h.fail("with nothing to ask, a node must have no answer");
            return;
        }
        if (Permissions.has(player, Permissions.warpNode("vault")) != BspCore.isAdmin(player)) {
            h.fail("without a permission plugin, a node is the admin list's answer");
            return;
        }
        h.succeed();
    }
}
