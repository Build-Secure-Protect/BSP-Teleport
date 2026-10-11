package com.mrgregles.bsp_teleport.api;

import net.minecraft.server.level.ServerPlayer;

/**
 * What other mods may call. Everything outside this package may change without notice.
 *
 * <p>These are forced moves: no warm-up, cooldown, cost or zone check. They are meant for admin-defined
 * triggers (Region Guard) and other mods' own rules, which have already decided the player must move.
 */
public interface BspTeleportApi {
    /** Raised whenever a method is added or changed. */
    int API_VERSION = 1;

    static BspTeleportApi get() {
        return BspTeleportApiHolder.INSTANCE;
    }

    /** This server's name in the proxy (config {@code network.serverName}). */
    String serverName();

    /**
     * Moves a player to a destination on this or another server. A cross-server move sends the player through the
     * proxy and lands them on arrival; returns false when the move could not even be started.
     */
    boolean teleport(ServerPlayer player, Destination destination);

    /** Sends a player to another server through the proxy, landing wherever that server puts them. */
    boolean sendToServer(ServerPlayer player, String server);

    /** Sends every player on this server to another server. Safe to call while the server is stopping. */
    int sendAllToServer(String server);
}
