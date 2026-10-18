package com.mrgregles.bsp_teleport.item;

import com.mrgregles.bsp_teleport.perm.Permissions;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** The Portal Core and Portal Frame as items: only admins can place them (D53). */
public class PortalBlockItem extends BlockItem {
    public PortalBlockItem(Block block) {
        super(block, new Properties().rarity(Rarity.EPIC));
    }

    @Override
    protected boolean canPlace(BlockPlaceContext ctx, BlockState state) {
        if (ctx.getPlayer() instanceof ServerPlayer player && !Permissions.isAdmin(player)) {
            player.displayClientMessage(Component.translatable("message.bsp_teleport.portal.admin_only").withStyle(ChatFormatting.RED), true);
            player.inventoryMenu.sendAllDataToRemote();
            return false;
        }
        return super.canPlace(ctx, state);
    }
}
