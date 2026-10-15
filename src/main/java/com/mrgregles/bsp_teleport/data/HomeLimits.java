package com.mrgregles.bsp_teleport.data;

import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.compat.BspCore;
import net.minecraft.server.level.ServerPlayer;

/**
 * How many homes a player may use: the config's slots plus what their totem upgrades add.
 *
 * <p>Homes above the count are kept but locked (owner decision D19), oldest homes first in.
 */
public final class HomeLimits {
    private HomeLimits() {
    }

    public static int slots(ServerPlayer player) {
        return TeleportConfig.getOr(TeleportConfig.HOME_SLOTS, 3) + BspCore.extraHomeSlots(player);
    }

    /** Whether the home at this index in the player's list (oldest first) is usable. */
    public static boolean unlocked(ServerPlayer player, int index) {
        return index >= 0 && index < slots(player);
    }
}
