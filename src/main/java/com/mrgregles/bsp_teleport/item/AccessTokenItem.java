package com.mrgregles.bsp_teleport.item;

import com.mrgregles.bsp_teleport.access.AccessGrant;
import com.mrgregles.bsp_teleport.access.AccessService;
import com.mrgregles.bsp_teleport.access.TokenAssignment;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * One of the twenty Access Tokens (D73, D74): a server's, region's, portal's or warp's symbol. The stack says whether
 * it grants rent (and for how long) or full access; right-clicking uses one (D75). Sold by BSP-Core's shops as any item.
 */
public class AccessTokenItem extends Item {
    private static final String TIER = "Tier", HOURS = "Hours";
    private final int index;

    public AccessTokenItem(int index) {
        super(new Properties().rarity(Rarity.RARE));
        this.index = index;
    }

    /** 1 to 20. */
    public int index() {
        return index;
    }

    /** A stack an admin puts in a shop: rent for so many hours (0 = the assignment's length), or full. */
    public ItemStack stack(AccessGrant.Tier tier, int hours) {
        ItemStack s = new ItemStack(this);
        CompoundTag tag = s.getOrCreateTag();
        tag.putString(TIER, tier.name());
        tag.putInt(HOURS, hours);
        return s;
    }

    /** FULL or RENT; a plain stack without the tag counts as rent. */
    public static AccessGrant.Tier tierOf(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && AccessGrant.Tier.FULL.name().equals(tag.getString(TIER)) ? AccessGrant.Tier.FULL : AccessGrant.Tier.RENT;
    }

    /** The rent hours the stack carries, or 0 for the assignment's length. */
    public static int hoursOf(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? 0 : tag.getInt(HOURS);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer sp) {
            AccessService.useToken(sp, stack);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines, TooltipFlag flag) {
        TokenAssignment token = com.mrgregles.bsp_teleport.client.ClientAccess.token(index);
        if (token == null) {
            lines.add(Component.translatable("message.bsp_teleport.access.tooltip_none").withStyle(ChatFormatting.GRAY));
            return;
        }
        lines.add(Component.literal(token.target()).withStyle(ChatFormatting.LIGHT_PURPLE));
        if (tierOf(stack) == AccessGrant.Tier.FULL) {
            lines.add(Component.translatable("message.bsp_teleport.access.tooltip_full").withStyle(ChatFormatting.GOLD));
        } else {
            int hours = hoursOf(stack) > 0 ? hoursOf(stack) : token.rentHours();
            lines.add(Component.translatable("message.bsp_teleport.access.tooltip_rent", hours).withStyle(ChatFormatting.GOLD));
        }
        lines.add(Component.translatable("message.bsp_teleport.access.tooltip_use").withStyle(ChatFormatting.GRAY));
    }
}
