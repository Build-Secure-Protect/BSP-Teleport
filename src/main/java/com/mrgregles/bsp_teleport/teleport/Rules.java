package com.mrgregles.bsp_teleport.teleport;

import com.mrgregles.bsp_teleport.compat.BspCore;
import com.mrgregles.bsp_teleport.compat.RegionGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;

/** Where teleports and homes are allowed: both Region Guard's flags and BSP-Core's Anti Totem zones must agree (D25). */
public final class Rules {
    private Rules() {
    }

    public static boolean canTeleportFrom(ServerLevel level, BlockPos pos, @Nullable ServerPlayer player) {
        return RegionGuard.canTeleportFrom(level, pos, player) && BspCore.canTeleportFrom(level, pos, player);
    }

    public static boolean canTeleportTo(ServerLevel level, BlockPos pos, @Nullable ServerPlayer player) {
        return RegionGuard.canTeleportTo(level, pos, player) && BspCore.canTeleportTo(level, pos, player);
    }

    public static boolean canSetHome(ServerLevel level, BlockPos pos, @Nullable ServerPlayer player) {
        return RegionGuard.canSetHome(level, pos, player) && BspCore.canSetHome(level, pos, player);
    }
}
