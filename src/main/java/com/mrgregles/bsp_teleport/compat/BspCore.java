package com.mrgregles.bsp_teleport.compat;

import com.mrgregles.bsp_core.admin.Admins;
import net.minecraft.world.entity.player.Player;

/**
 * The only class that touches BSP-Core.
 *
 * <p>BSP-Core has no stable API yet, so every call into it goes through here: a rename there breaks this file and
 * nothing else. Move these onto {@code com.mrgregles.bsp_core.api} once it exists.
 */
public final class BspCore {
    private BspCore() {
    }

    public static boolean isAdmin(Player player) {
        return Admins.isAdmin(player);
    }

    public static boolean isModerator(Player player) {
        return Admins.isModerator(player);
    }
}
