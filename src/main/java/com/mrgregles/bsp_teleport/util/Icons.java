package com.mrgregles.bsp_teleport.util;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

/** Item ids used as icons for homes and warps. */
public final class Icons {
    public static final String DEFAULT_HOME = "minecraft:red_bed";
    public static final String DEFAULT_WARP = "minecraft:ender_pearl";

    private Icons() {
    }

    /** The id of the item in the player's main hand, or empty. */
    public static String heldItemId(Player player) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            return "";
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(held.getItem());
        return id == null ? "" : id.toString();
    }

    /** A stack for an icon id, or the fallback when it is empty or unknown. */
    public static ItemStack stack(String icon, String fallback) {
        ItemStack s = lookup(icon);
        return s.isEmpty() ? lookup(fallback) : s;
    }

    private static ItemStack lookup(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            return ItemStack.EMPTY;
        }
        Item item = ForgeRegistries.ITEMS.getValue(key);
        return item == null || item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }
}
