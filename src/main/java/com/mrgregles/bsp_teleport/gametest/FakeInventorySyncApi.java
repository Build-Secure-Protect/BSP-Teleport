package com.mrgregles.bsp_teleport.gametest;

import com.mrgregles.bsp_teleport.compat.BspCore;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * A stand-in for {@code com.mrgregles.bsp_inventorysync.api.InventorySyncApi} with the same two static signatures, so
 * the GameTest can point {@code compat/BspInventorySync.resolve} at it and go through the adapter's real lookup. Each
 * call writes a step into {@link #STEPS}, with whether BSP-Core already held the transfer open at that moment.
 */
public final class FakeInventorySyncApi {
    static final List<String> STEPS = new ArrayList<>();
    static boolean throwOnBegin;

    private FakeInventorySyncApi() {
    }

    static void reset() {
        STEPS.clear();
        throwOnBegin = false;
    }

    public static boolean beginTransfer(ServerPlayer player, String targetServer) {
        if (throwOnBegin) {
            throw new IllegalStateException("stand-in failure");
        }
        STEPS.add("inventory " + targetServer + " core=" + BspCore.isTransferring(player.getUUID()));
        return true;
    }

    public static boolean cancelTransfer(ServerPlayer player) {
        STEPS.add("cancel");
        return true;
    }
}
