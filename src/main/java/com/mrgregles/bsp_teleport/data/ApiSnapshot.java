package com.mrgregles.bsp_teleport.data;

import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.api.Destination;
import com.mrgregles.bsp_teleport.api.WarpInfo;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * T89 (D204): the warps and spawns other mods read through {@code BspTeleportApi.warps()} and {@code spawn(server)}.
 * {@link TeleportData} is the live store and belongs to the server thread; every change to its warps or spawn rebuilds
 * these read-only copies there, so the API can be read from any thread (Server Utilities' web panel).
 */
public final class ApiSnapshot {
    private static volatile List<WarpInfo> warps = List.of();
    @Nullable
    private static volatile Position ownSpawn;
    /** D205: other servers' /setspawn points from the shared table, keyed by lower-case server name. */
    private static volatile Map<String, Position> otherSpawns = Map.of();

    private ApiSnapshot() {
    }

    /** Called by {@link TeleportData} on the server thread after its warps changed. */
    static void warpsChanged(Collection<Warp> all) {
        List<WarpInfo> list = new ArrayList<>(all.size());
        for (Warp w : all) {
            list.add(info(w));
        }
        list.sort(Comparator.comparing(WarpInfo::name, String.CASE_INSENSITIVE_ORDER));
        warps = List.copyOf(list);
    }

    /** Called by {@link TeleportData} after this server's own /setspawn point changed. */
    static void ownSpawnChanged(@Nullable Position spawn) {
        ownSpawn = spawn;
    }

    /** D205: every server's /setspawn point, as read from the shared table. */
    public static void replaceSpawns(Map<String, Position> byServer) {
        Map<String, Position> copy = new HashMap<>();
        byServer.forEach((server, at) -> copy.put(key(server), at));
        otherSpawns = Map.copyOf(copy);
    }

    /** Forgets everything, when a server stops (a single-player world closing must not leak into the next). */
    public static void clear() {
        warps = List.of();
        ownSpawn = null;
        otherSpawns = Map.of();
    }

    public static List<WarpInfo> warps() {
        return warps;
    }

    /** D206, D207: a server's /setspawn point; "" or this server's name (any case) is this server. */
    public static Optional<Destination> spawn(@Nullable String server) {
        Position at = isHere(server) ? ownSpawn : otherSpawns.get(key(server));
        return Optional.ofNullable(at).map(ApiSnapshot::destination);
    }

    static boolean isHere(@Nullable String server) {
        return server == null || server.isEmpty() || server.equalsIgnoreCase(TeleportConfig.serverName());
    }

    static WarpInfo info(Warp w) {
        return new WarpInfo(w.name(), destination(w.position()), w.restricted(), w.icon(), w.portal());
    }

    /** The position with its server always named (never empty), so the panel can show it; {@code teleport} treats this server's name as a local move. */
    static Destination destination(Position p) {
        return new Destination(p.server(), p.world(), p.x(), p.y(), p.z(), p.yaw(), p.pitch(), "");
    }

    private static String key(String server) {
        return server.toLowerCase(Locale.ROOT);
    }
}
