package com.mrgregles.bsp_teleport.compat;

import com.mrgregles.bsp_teleport.BSPTeleport;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * BSP-InventorySync, optional at runtime and never compiled against (request T1 from that mod). Just before a proxy
 * switch, {@code InventorySyncApi.beginTransfer} starts the inventory save, so the arriving server finds the snapshot
 * ready instead of waiting for the logout's save. Only a speed-up: the sync works without it.
 *
 * <p>The two static calls are looked up once by reflection when the mod is loaded. Absent mod or failed lookup: every
 * call does nothing. A throw from the other mod is logged here and never reaches the teleport.
 */
public final class BspInventorySync {
    public static final String MODID = "bsp_inventorysync";
    public static final String API = "com.mrgregles.bsp_inventorysync.api.InventorySyncApi";

    private static final MethodType BEGIN = MethodType.methodType(boolean.class, ServerPlayer.class, String.class);
    private static final MethodType CANCEL = MethodType.methodType(boolean.class, ServerPlayer.class);

    @Nullable
    private static MethodHandle begin;
    @Nullable
    private static MethodHandle cancel;

    private BspInventorySync() {
    }

    /** At mod construction: looks the api up when the mod is loaded, and says in one log line which way it went. */
    public static void init() {
        if (!ModList.get().isLoaded(MODID)) {
            BSPTeleport.LOGGER.info("BSP-InventorySync not loaded: departures leave the inventory save to the logout");
            return;
        }
        String failure = resolve(API);
        if (failure == null) {
            BSPTeleport.LOGGER.info("BSP-InventorySync found: departures start the inventory save before the switch");
        } else {
            BSPTeleport.LOGGER.warn("BSP-InventorySync is loaded but its api could not be used ({}): departures leave the inventory save to the logout", failure);
        }
    }

    /**
     * Looks up {@code beginTransfer(ServerPlayer, String)} and {@code cancelTransfer(ServerPlayer)} on the named class
     * and uses them from now on. Returns null when both were found, else why not (and the calls stay off). Public for
     * the GameTest, which points it at a stand-in class.
     */
    @Nullable
    public static String resolve(String className) {
        begin = null;
        cancel = null;
        try {
            Class<?> api = Class.forName(className, false, BspInventorySync.class.getClassLoader());
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle b = lookup.findStatic(api, "beginTransfer", BEGIN);
            MethodHandle c = lookup.findStatic(api, "cancelTransfer", CANCEL);
            begin = b;
            cancel = c;
            return null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return e.toString();
        }
    }

    /** Switches the calls off again (the GameTest's clean-up). */
    public static void clear() {
        begin = null;
        cancel = null;
    }

    public static boolean isPresent() {
        return begin != null;
    }

    /**
     * Just before the proxy switch, after BSP-Core has taken the totems: starts the save of this player's inventory for
     * the target server. Returns at once. False when absent, when the sync is off on this server, when the player's own
     * data is still arriving, or when the call failed; the switch goes ahead either way.
     */
    public static boolean beginTransfer(ServerPlayer player, String targetServer) {
        MethodHandle handle = begin;
        if (handle == null) {
            return false;
        }
        try {
            return (boolean) handle.invokeExact(player, targetServer);
        } catch (Throwable t) {
            BSPTeleport.LOGGER.warn("BSP-InventorySync beginTransfer failed for {}, switching anyway: {}", player.getGameProfile().getName(), t.toString());
            return false;
        }
    }

    /** Undoes {@link #beginTransfer} for a departure that did not happen. Nothing calls it yet: no departure is undone after it starts. */
    public static boolean cancelTransfer(ServerPlayer player) {
        MethodHandle handle = cancel;
        if (handle == null) {
            return false;
        }
        try {
            return (boolean) handle.invokeExact(player);
        } catch (Throwable t) {
            BSPTeleport.LOGGER.warn("BSP-InventorySync cancelTransfer failed for {}: {}", player.getGameProfile().getName(), t.toString());
            return false;
        }
    }
}
