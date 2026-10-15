package com.mrgregles.bsp_teleport.network;

import com.mrgregles.bsp_teleport.TeleportConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which servers are up and who is on them, from the player lists every server publishes (on join, leave, and as a
 * heartbeat). A server that said goodbye, or has been silent for {@link #STALE_MS}, is treated as down.
 */
public final class PlayerDirectory {
    static final long STALE_MS = 90_000L;
    private static final Map<String, Entry> SERVERS = new ConcurrentHashMap<>();

    private record Entry(Set<String> names, long lastSeen) {
    }

    private PlayerDirectory() {
    }

    public static void update(String server, List<String> names) {
        SERVERS.put(server, new Entry(new TreeSet<>(names), System.currentTimeMillis()));
    }

    public static void markDown(String server) {
        SERVERS.remove(server);
    }

    public static void clear() {
        SERVERS.clear();
    }

    public static void prune() {
        long cutoff = System.currentTimeMillis() - STALE_MS;
        SERVERS.entrySet().removeIf(e -> e.getValue().lastSeen() < cutoff);
    }

    /** Whether the server reported recently. This server is always online. */
    public static boolean isOnline(String server) {
        if (server.equals(TeleportConfig.serverName())) {
            return true;
        }
        Entry e = SERVERS.get(server);
        return e != null && System.currentTimeMillis() - e.lastSeen() < STALE_MS;
    }

    /** The server a player is on, or null when no server reported them. This server's own players are not listed. */
    @Nullable
    public static String serverOf(String name) {
        for (Map.Entry<String, Entry> e : SERVERS.entrySet()) {
            for (String n : e.getValue().names()) {
                if (n.equalsIgnoreCase(name)) {
                    return e.getKey();
                }
            }
        }
        return null;
    }

    /** Every name known on other servers plus this one's, for command suggestions. */
    public static List<String> allNames(MinecraftServer server) {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            names.add(p.getGameProfile().getName());
        }
        SERVERS.values().forEach(e -> names.addAll(e.names()));
        return new ArrayList<>(names);
    }

    public static List<String> servers() {
        prune();
        List<String> servers = new ArrayList<>(SERVERS.keySet());
        if (!servers.contains(TeleportConfig.serverName())) {
            servers.add(TeleportConfig.serverName());
        }
        servers.sort(String.CASE_INSENSITIVE_ORDER);
        return servers;
    }

    /** Every reported server with its names (this server not included). */
    public static Map<String, List<String>> snapshot() {
        prune();
        Map<String, List<String>> out = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        SERVERS.forEach((server, e) -> out.put(server, new ArrayList<>(e.names())));
        return out;
    }

    public static String describe() {
        prune();
        if (SERVERS.isEmpty()) {
            return "no other servers reported";
        }
        StringBuilder sb = new StringBuilder();
        SERVERS.forEach((server, e) -> sb.append(server).append(" (").append(e.names().size()).append("): ")
                .append(e.names().isEmpty() ? "nobody" : String.join(", ", e.names())).append(". "));
        return sb.toString().trim();
    }
}
