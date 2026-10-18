package com.mrgregles.bsp_teleport.block;

import com.mrgregles.bsp_teleport.compat.BspCore;
import com.mrgregles.bsp_teleport.perm.Permissions;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/** Shared rules of the portal blocks (D53): only admins place or remove them. */
final class PortalBlocks {
    private PortalBlocks() {
    }

    static boolean isAdmin(Player player) {
        return player instanceof ServerPlayer sp ? Permissions.isAdmin(sp) : BspCore.isAdmin(player);
    }

    /** True when the player may break the block; tells a non-admin why not. */
    static boolean mayBreak(Player player) {
        if (isAdmin(player)) {
            return true;
        }
        if (player instanceof ServerPlayer sp) {
            sp.displayClientMessage(Component.translatable("message.bsp_teleport.portal.admin_only").withStyle(ChatFormatting.RED), true);
        }
        return false;
    }
}
