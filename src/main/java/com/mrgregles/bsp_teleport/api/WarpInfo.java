package com.mrgregles.bsp_teleport.api;

/**
 * A warp as other mods see it (API version 2).
 *
 * @param name       the warp's name, lower case, as players type it in {@code /warp}
 * @param position   where it leads; {@code server} is always the name of the server it is on, never empty
 * @param restricted whether a player needs the permission node {@code bsp_teleport.warp.<name>} to use it
 * @param icon       an item id shown beside it, or empty for the default
 * @param portal     a portal warp: only portals lead to it, and players never see it in {@code /warps} or on the band
 */
public record WarpInfo(String name, Destination position, boolean restricted, String icon, boolean portal) {
}
