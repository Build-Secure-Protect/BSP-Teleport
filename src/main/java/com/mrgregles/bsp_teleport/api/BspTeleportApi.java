package com.mrgregles.bsp_teleport.api;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * What other mods may call. Everything outside this package may change without notice.
 *
 * <p>The moves are forced moves: no warm-up, cooldown, cost or zone check. They are meant for admin-defined
 * triggers (Region Guard), staff tools and other mods' own rules, which have already decided the player must move.
 * {@code docs/API.md} says what a move to another server does on the way and on arrival.
 */
public interface BspTeleportApi {
    /** Raised whenever a method is added or changed. 2: {@link #warps()} and {@link #spawn(String)}. */
    int API_VERSION = 2;

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

    /**
     * Sends a line to every other server on the mod's Redis channel; {@code handler} on those servers receives
     * {@code (sourceServer, payload)} on the server thread. False when messaging is off.
     */
    boolean publish(String channel, String payload);

    /** Registers a handler for a channel. One handler per channel; the last registration wins. */
    void subscribe(String channel, BiConsumer<String, String> handler);

    /** Whether Redis messaging is configured and started. */
    boolean messagingAvailable();

    /**
     * Every warp this server knows, portal warps included, sorted by name (API 2). With the shared database that is
     * every warp on the network; without it, this world's own. Safe to call from any thread; the list cannot be changed.
     */
    List<WarpInfo> warps();

    /**
     * A server's {@code /setspawn} point (API 2). {@code ""} or this server's name (any case) is this server; another
     * server's is known only with the shared database. Empty when that server has none, or is unknown. The network
     * spawn warp is not a fallback: it is in {@link #warps()} under its own name. Safe to call from any thread.
     */
    Optional<Destination> spawn(String server);
}
