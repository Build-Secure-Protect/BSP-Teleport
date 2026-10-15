package com.mrgregles.bsp_teleport.data;

import net.minecraft.nbt.CompoundTag;

/**
 * An admin-set warp.
 *
 * @param cost       Shatter Coin value charged per use; 0 for free
 * @param restricted whether a player needs the warp's permission node to use it
 */
public record Warp(String name, Position position, int cost, boolean restricted) {
    public CompoundTag save() {
        CompoundTag tag = position.save();
        tag.putString("Name", name);
        tag.putInt("Cost", cost);
        tag.putBoolean("Restricted", restricted);
        return tag;
    }

    public static Warp load(CompoundTag tag) {
        return new Warp(tag.getString("Name"), Position.load(tag), tag.getInt("Cost"), tag.getBoolean("Restricted"));
    }
}
