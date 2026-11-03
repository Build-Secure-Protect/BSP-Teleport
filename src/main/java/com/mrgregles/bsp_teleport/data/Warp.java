package com.mrgregles.bsp_teleport.data;

import net.minecraft.nbt.CompoundTag;

/**
 * An admin-set warp.
 *
 * @param cost       what one use costs, in pieces or in Copper coin value (see {@code pieces}); 0 for free
 * @param restricted whether a player needs the warp's permission node to use it
 * @param icon       an item id shown beside it, or empty for the default
 * @param portal     a portal warp (D55): only portals lead to it; the band, {@code /warp} and {@code /warps} never show it
 * @param rules      the access rules (D86) in {@link com.mrgregles.bsp_teleport.access.AccessRule#serializeList} form; empty for none
 * @param pieces     D163: the cost comes out of the BSP-Core bank in Shatter Pieces; false for Shatter Coins carried. New warps
 *                   start in pieces, warps saved before D163 load as coins.
 */
public record Warp(String name, Position position, int cost, boolean restricted, String icon, boolean portal, String rules, boolean pieces) {
    public Warp(String name, Position position, int cost, boolean restricted) {
        this(name, position, cost, restricted, "", false, "", true);
    }

    public Warp(String name, Position position, int cost, boolean restricted, String icon) {
        this(name, position, cost, restricted, icon, false, "", true);
    }

    public Warp(String name, Position position, int cost, boolean restricted, String icon, boolean portal, String rules) {
        this(name, position, cost, restricted, icon, portal, rules, true);
    }

    public Warp withPosition(Position position) {
        return new Warp(name, position, cost, restricted, icon, portal, rules, pieces);
    }

    public Warp withCost(int cost) {
        return new Warp(name, position, cost, restricted, icon, portal, rules, pieces);
    }

    public Warp withCost(int cost, boolean pieces) {
        return new Warp(name, position, cost, restricted, icon, portal, rules, pieces);
    }

    public Warp withRestricted(boolean restricted) {
        return new Warp(name, position, cost, restricted, icon, portal, rules, pieces);
    }

    public Warp withIcon(String icon) {
        return new Warp(name, position, cost, restricted, icon, portal, rules, pieces);
    }

    public Warp(String name, Position position, int cost, boolean restricted, String icon, boolean portal) {
        this(name, position, cost, restricted, icon, portal, "", true);
    }

    public Warp withPortal(boolean portal) {
        return new Warp(name, position, cost, restricted, icon, portal, rules, pieces);
    }

    public Warp withRules(String rules) {
        return new Warp(name, position, cost, restricted, icon, portal, rules, pieces);
    }

    public CompoundTag save() {
        CompoundTag tag = position.save();
        tag.putString("Name", name);
        tag.putInt("Cost", cost);
        tag.putBoolean("Restricted", restricted);
        tag.putString("Icon", icon);
        tag.putBoolean("Portal", portal);
        tag.putString("Rules", rules);
        tag.putBoolean("CostPieces", pieces);
        return tag;
    }

    public static Warp load(CompoundTag tag) {
        return new Warp(tag.getString("Name"), Position.load(tag), tag.getInt("Cost"), tag.getBoolean("Restricted"), tag.getString("Icon"), tag.getBoolean("Portal"), tag.getString("Rules"),
                tag.getBoolean("CostPieces"));
    }
}
