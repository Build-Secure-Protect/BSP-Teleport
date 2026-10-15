package com.mrgregles.bsp_teleport.perm;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.compat.BspCore;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Permission nodes, answered by LuckPerms through Bukkit on Mohist.
 *
 * <p>Mohist gives every entity a {@code getBukkitEntity()}; the Bukkit player's {@code hasPermission} is what
 * LuckPerms answers. On a server without Bukkit there is no permission plugin to ask, so BSP-Core's admin list
 * stands in: admins may do everything, everyone else only what needs no node.
 */
public final class Permissions {
    public static final String ADMIN = "bsp_teleport.admin";
    public static final String BYPASS_COOLDOWN = "bsp_teleport.bypass.cooldown";
    public static final String BYPASS_WARMUP = "bsp_teleport.bypass.warmup";
    public static final String WARP_PREFIX = "bsp_teleport.warp.";

    private static Method getBukkitEntity;
    private static Method hasPermission;
    private static boolean probed;

    private Permissions() {
    }

    public static String warpNode(String warp) {
        return WARP_PREFIX + warp;
    }

    public static boolean isAdmin(ServerPlayer player) {
        return BspCore.isAdmin(player) || node(player, ADMIN).orElse(false);
    }

    /** Whether the player has the node; without a permission plugin, admins have every node. */
    public static boolean has(ServerPlayer player, String node) {
        return node(player, node).orElseGet(() -> isAdmin(player));
    }

    /** The plugin's answer, or empty when there is no plugin to ask. */
    public static Optional<Boolean> node(ServerPlayer player, String node) {
        probe(player);
        if (hasPermission == null) {
            return Optional.empty();
        }
        try {
            Object bukkitPlayer = getBukkitEntity.invoke(player);
            return Optional.of((Boolean) hasPermission.invoke(bukkitPlayer, node));
        } catch (ReflectiveOperationException | RuntimeException e) {
            BSPTeleport.LOGGER.warn("Permission check through Bukkit failed: {}", e.toString());
            return Optional.empty();
        }
    }

    private static synchronized void probe(ServerPlayer player) {
        if (probed) {
            return;
        }
        probed = true;
        try {
            Method entity = player.getClass().getMethod("getBukkitEntity");
            Object bukkitPlayer = entity.invoke(player);
            Method has = bukkitPlayer.getClass().getMethod("hasPermission", String.class);
            has.setAccessible(true);
            getBukkitEntity = entity;
            hasPermission = has;
            BSPTeleport.LOGGER.info("Permissions are answered by Bukkit ({})", bukkitPlayer.getClass().getName());
        } catch (ReflectiveOperationException | RuntimeException e) {
            BSPTeleport.LOGGER.info("No Bukkit on this server: permission nodes fall back to BSP-Core's admin list");
        }
    }
}
