package com.mrgregles.bsp_teleport.compat;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.api.BspTeleportApi;
import com.mrgregles.bsp_teleport.api.Destination;
import com.mrgregles.bsp_teleport.teleport.PendingLandings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;

import java.util.UUID;

/**
 * The only class that touches BSP Region Guard, which is optional at runtime.
 *
 * <p>Every Region Guard class is referenced from the nested {@link Impl} only, which is never loaded unless the mod is
 * present. Without Region Guard every check allows, and there is no shared message store.
 */
public final class RegionGuard {
    public static final String MODID = "bsp_region_guard";
    /** Message kind this mod publishes through Region Guard's store: payload is the player's uuid and a destination. */
    public static final String KIND_LANDING = "bsp_teleport.landing";

    private static final boolean PRESENT = ModList.get().isLoaded(MODID);

    private RegionGuard() {
    }

    public static boolean isPresent() {
        return PRESENT;
    }

    /** Registers the trigger and message listeners; call once from the mod constructor. */
    public static void init() {
        if (PRESENT) {
            Impl.register();
            BSPTeleport.LOGGER.info("Region Guard found: region flags and triggers are on");
        }
    }

    public static boolean canTeleportFrom(ServerLevel level, BlockPos pos, ServerPlayer player) {
        return !PRESENT || Impl.allowsTeleportOut(level, pos, player);
    }

    public static boolean canTeleportTo(ServerLevel level, BlockPos pos, ServerPlayer player) {
        return !PRESENT || Impl.allowsTeleportIn(level, pos, player);
    }

    public static boolean canSetHome(ServerLevel level, BlockPos pos, ServerPlayer player) {
        return !PRESENT || Impl.allowsSetHome(level, pos, player);
    }

    /** Tells the destination server to land this player on arrival. False when there is no store to tell it through. */
    public static boolean announceLanding(UUID player, Destination destination) {
        return PRESENT && Impl.publish(KIND_LANDING, player + Destination.SEPARATOR + destination.serialize());
    }

    /** Only this class names Region Guard's types, so the outer class loads without the mod. */
    private static final class Impl {
        static void register() {
            MinecraftForge.EVENT_BUS.addListener(Impl::onTrigger);
            MinecraftForge.EVENT_BUS.addListener(Impl::onRemoteMessage);
        }

        static boolean allowsTeleportOut(ServerLevel level, BlockPos pos, ServerPlayer player) {
            return allows(level, pos, com.mrgregles.bsp_region_guard.flag.Flags.TELEPORT_OUT, player);
        }

        static boolean allowsTeleportIn(ServerLevel level, BlockPos pos, ServerPlayer player) {
            return allows(level, pos, com.mrgregles.bsp_region_guard.flag.Flags.TELEPORT_IN, player);
        }

        static boolean allowsSetHome(ServerLevel level, BlockPos pos, ServerPlayer player) {
            return allows(level, pos, com.mrgregles.bsp_region_guard.flag.Flags.SET_HOME, player);
        }

        private static boolean allows(ServerLevel level, BlockPos pos, com.mrgregles.bsp_region_guard.flag.StateFlag flag, ServerPlayer player) {
            try {
                return com.mrgregles.bsp_region_guard.api.RegionGuardApi.get().regions().allows(level, pos, flag, player);
            } catch (RuntimeException e) {
                BSPTeleport.LOGGER.warn("Region Guard check failed, allowing: {}", e.toString());
                return true;
            }
        }

        static boolean publish(String kind, String payload) {
            try {
                return com.mrgregles.bsp_region_guard.api.RegionGuardApi.get().publish(kind, "", "", payload);
            } catch (RuntimeException e) {
                BSPTeleport.LOGGER.warn("Region Guard publish failed: {}", e.toString());
                return false;
            }
        }

        /** A region trigger with a cross-server destination is ours: take it over and move the player. */
        static void onTrigger(com.mrgregles.bsp_region_guard.api.event.RegionTriggerEvent event) {
            if (!event.crossServer()) {
                return;
            }
            event.setCanceled(true);
            Destination destination = new Destination(event.destinationServer(), event.destinationWorld(),
                    event.x(), event.y(), event.z(), event.message());
            BspTeleportApi.get().teleport(event.player(), destination);
        }

        /** A landing announced by another server for a player heading here. */
        static void onRemoteMessage(com.mrgregles.bsp_region_guard.api.event.RemoteMessageEvent event) {
            if (!KIND_LANDING.equals(event.kind())) {
                return;
            }
            String[] parts = event.payload().split(Destination.SEPARATOR, 2);
            if (parts.length < 2) {
                return;
            }
            Destination destination;
            UUID uuid;
            try {
                uuid = UUID.fromString(parts[0]);
                destination = Destination.parse(parts[1]);
            } catch (IllegalArgumentException e) {
                BSPTeleport.LOGGER.warn("Bad landing message from {}: {}", event.source(), event.payload());
                return;
            }
            if (destination.server().equals(BspTeleportApi.get().serverName())) {
                PendingLandings.expect(uuid, destination);
            }
        }
    }
}
