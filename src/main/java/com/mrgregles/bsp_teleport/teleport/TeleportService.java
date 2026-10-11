package com.mrgregles.bsp_teleport.teleport;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.api.BspTeleportApi;
import com.mrgregles.bsp_teleport.api.Destination;
import com.mrgregles.bsp_teleport.compat.RegionGuard;
import com.mrgregles.bsp_teleport.network.ProxyMessenger;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.List;

/**
 * Forced moves, the API's implementation.
 *
 * <p>Cross-server moves need a message store the target server also reads. Until this mod has its own (MySQL and
 * Redis, phase 2), the only one in the network is Region Guard's, so the landing is announced through it when
 * Region Guard is present; otherwise the player is still sent, and lands wherever the target server puts them.
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
        if (!destination.crossServer() || destination.server().equals(serverName())) {
            return moveHere(player, destination);
        }
        if (!RegionGuard.announceLanding(player.getUUID(), destination)) {
            BSPTeleport.LOGGER.warn("No shared message store: {} is sent to '{}' but will land wherever that server puts them",
                    player.getGameProfile().getName(), destination.server());
        }
        if (!destination.message().isEmpty()) {
            player.displayClientMessage(Component.literal(destination.message()), false);
        }
        return ProxyMessenger.connect(player, destination.server());
    }

    @Override
    public boolean sendToServer(ServerPlayer player, String server) {
        return ProxyMessenger.connect(player, server);
    }

    @Override
    public int sendAllToServer(String server) {
        MinecraftServer mc = ServerLifecycleHooks.getCurrentServer();
        if (mc == null) {
            return 0;
        }
        int sent = 0;
        for (ServerPlayer player : new ArrayList<>(mc.getPlayerList().getPlayers())) {
            if (ProxyMessenger.connect(player, server)) {
                sent++;
            }
        }
        return sent;
    }

    /** Moves a player on this server, changing dimension if needed. */
    static boolean moveHere(ServerPlayer player, Destination destination) {
        ResourceLocation worldId = ResourceLocation.tryParse(destination.world());
        ServerLevel level = worldId == null ? null : player.server.getLevel(ResourceKey.create(Registries.DIMENSION, worldId));
        if (level == null) {
            BSPTeleport.LOGGER.warn("Cannot land {}: world '{}' does not exist here", player.getGameProfile().getName(), destination.world());
            return false;
        }
        player.teleportTo(level, destination.x(), destination.y(), destination.z(), player.getYRot(), player.getXRot());
        if (!destination.message().isEmpty()) {
            player.displayClientMessage(Component.literal(destination.message()), false);
        }
        return true;
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
