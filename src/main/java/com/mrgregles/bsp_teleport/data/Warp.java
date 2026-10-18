package com.mrgregles.bsp_teleport.data;

import net.minecraft.nbt.CompoundTag;

/**
 * An admin-set warp.
 *
 * @param cost       Shatter Coin value charged per use; 0 for free
 * @param restricted whether a player needs the warp's permission node to use it
 * @param icon       an item id shown beside it, or empty for the default
 * @param portal     a portal warp (D55): only portals lead to it; the band, {@code /warp} and {@code /warps} never show it
 */
public record Warp(String name, Position position, int cost, boolean restricted, String icon, boolean portal) {
    public Warp(String name, Position position, int cost, boolean restricted) {
        this(name, position, cost, restricted, "", false);
    }

    public Warp(String name, Position position, int cost, boolean restricted, String icon) {
        this(name, position, cost, restricted, icon, false);
    }

    public Warp withPosition(Position position) {
        return new Warp(name, position, cost, restricted, icon, portal);
    }

    public Warp withCost(int cost) {
        return new Warp(name, position, cost, restricted, icon, portal);
    }

    public Warp withRestricted(boolean restricted) {
        return new Warp(name, position, cost, restricted, icon, portal);
    }

    public Warp withIcon(String icon) {
        return new Warp(name, position, cost, restricted, icon, portal);
    }

    public Warp withPortal(boolean portal) {
        return new Warp(name, position, cost, restricted, icon, portal);
    }

    public CompoundTag save() {
        CompoundTag tag = position.save();
        tag.putString("Name", name);
        tag.putInt("Cost", cost);
        tag.putBoolean("Restricted", restricted);
        tag.putString("Icon", icon);
        tag.putBoolean("Portal", portal);
        return tag;
    }

    public static Warp load(CompoundTag tag) {
        return new Warp(tag.getString("Name"), Position.load(tag), tag.getInt("Cost"), tag.getBoolean("Restricted"), tag.getString("Icon"), tag.getBoolean("Portal"));
    }
}
