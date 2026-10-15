package com.mrgregles.bsp_teleport.teleport;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.api.BspTeleportApi;
import com.mrgregles.bsp_teleport.api.Destination;
import com.mrgregles.bsp_teleport.compat.BspCore;
import com.mrgregles.bsp_teleport.compat.RegionGuard;
import com.mrgregles.bsp_teleport.network.Messenger;
import com.mrgregles.bsp_teleport.network.ProxyMessenger;
import com.mrgregles.bsp_teleport.storage.Storage;
import com.mrgregles.bsp_teleport.util.Scheduler;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Forced moves, the API's implementation.
 *
 * <p>A cross-server move writes the landing to the shared database (or, without one, announces it through Region
 * Guard's store), tells BSP-Core the player is transferring, then asks the proxy to move them. The target server lands
 * them on arrival ({@link PendingLandings}).
 */
public final class TeleportService implements BspTeleportApi {
    public static final TeleportService INSTANCE = new TeleportService();

    private TeleportService() {
    }

    @Override
    public String serverName() {
        return TeleportConfig.serverName();
    }

    @Override
    public boolean teleport(ServerPlayer player, Destination destination) {
        if (player.isDeadOrDying() || player.hasDisconnected()) {
            return false;
        }
        // Every move, forced ones included, is a place /back can return to (an evacuated player comes home with it).
        Teleports.recordBack(player);
        if (!destination.crossServer() || destination.server().equals(serverName())) {
            return moveHere(player, destination);
        }
        if (player.connection == null) {
            return false;
        }
        UUID uuid = player.getUUID();
        if (!Storage.enabled() && !RegionGuard.announceLanding(uuid, destination)) {
            BSPTeleport.LOGGER.warn("No shared store: {} is sent to '{}' but will land wherever that server puts them",
                    player.getGameProfile().getName(), destination.server());
        }
        if (!destination.message().isEmpty()) {
            player.displayClientMessage(Component.literal(destination.message()), false);
        }
        Storage.savePending(uuid, destination, () -> leave(player, destination.server()));
        return true;
    }

    @Override
    public boolean sendToServer(ServerPlayer player, String server) {
        if (player.connection == null || server.isEmpty() || player.hasDisconnected()) {
            return false;
        }
        Teleports.recordBack(player);
        leave(player, server);
        return true;
    }

    @Override
    public int sendAllToServer(String server) {
        MinecraftServer mc = ServerLifecycleHooks.getCurrentServer();
        if (mc == null) {
            return 0;
        }
        int sent = 0;
        for (ServerPlayer player : new ArrayList<>(mc.getPlayerList().getPlayers())) {
            if (sendToServer(player, server)) {
                sent++;
            }
        }
        return sent;
    }

    @Override
    public boolean publish(String channel, String payload) {
        return Messenger.publishCustom(channel, payload);
    }

    @Override
    public void subscribe(String channel, BiConsumer<String, String> handler) {
        Messenger.subscribeCustom(channel, handler);
    }

    @Override
    public boolean messagingAvailable() {
        return Messenger.enabled();
    }

    /** The actual departure: off any mount, BSP-Core told, then the proxy asked. */
    private static void leave(ServerPlayer player, String server) {
        if (player.hasDisconnected()) {
            return;
        }
        dismount(player);
        BspCore.beginTransfer(player);
        ProxyMessenger.connect(player, server);
    }

    private static void dismount(ServerPlayer player) {
        player.stopRiding();
        for (Entity passenger : new ArrayList<>(player.getPassengers())) {
            passenger.stopRiding();
        }
    }

    /** Moves a player on this server, changing dimension if needed. */
    static boolean moveHere(ServerPlayer player, Destination destination) {
        ServerLevel level = levelOf(player.server, destination.world());
        if (level == null) {
            BSPTeleport.LOGGER.warn("Cannot land {}: world '{}' does not exist here", player.getGameProfile().getName(), destination.world());
            return false;
        }
        float yaw = destination.hasFacing() ? destination.yaw() : player.getYRot();
        float pitch = destination.hasFacing() ? destination.pitch() : player.getXRot();
        dismount(player);
        player.fallDistance = 0f;
        player.teleportTo(level, destination.x(), destination.y(), destination.z(), yaw, pitch);
        player.fallDistance = 0f;
        protect(player, destination.protect());
        com.mrgregles.bsp_teleport.util.Sounds.arrived(player);
        if (!destination.message().isEmpty()) {
            player.displayClientMessage(Component.literal(destination.message()), false);
        }
        return true;
    }

    /** Arrival protection: the destination's own seconds, or the config value when it says -1. */
    static void protect(ServerPlayer player, int requested) {
        int seconds = requested >= 0 ? requested : TeleportConfig.getOr(TeleportConfig.INVULNERABLE_SECONDS, 0);
        if (seconds <= 0 || player.isInvulnerable()) {
            return;
        }
        player.setInvulnerable(true);
        Scheduler.later(seconds * 20, () -> {
            if (!player.isRemoved()) {
                player.setInvulnerable(false);
            }
        });
    }

    /** The level for a dimension id on this server, or null. */
    @Nullable
    public static ServerLevel levelOf(MinecraftServer server, String world) {
        ResourceLocation id = ResourceLocation.tryParse(world);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    /** The dimension ids this server has, for messages and checks. */
    public static List<String> worldIds(MinecraftServer server) {
        List<String> ids = new ArrayList<>();
        for (ResourceKey<Level> key : server.levelKeys()) {
            ids.add(key.location().toString());
        }
        return ids;
    }
}
