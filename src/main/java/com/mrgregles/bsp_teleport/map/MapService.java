package com.mrgregles.bsp_teleport.map;

import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.compat.RegionGuard;
import com.mrgregles.bsp_teleport.data.PortalRow;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.data.Warp;
import com.mrgregles.bsp_teleport.network.PlayerDirectory;
import com.mrgregles.bsp_teleport.portal.PortalDestination;
import com.mrgregles.bsp_teleport.portal.Portals;
import com.mrgregles.bsp_teleport.teleport.TeleportActions;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** D201: builds the band's MAP tab for one player. */
public final class MapService {
    /** As {@link MapData}'s decoder caps them. */
    private static final int MAX_WARP_NAMES = 16, MAX = 256;

    private MapService() {
    }

    public static MapData build(ServerPlayer player) {
        return build(player, RegionGuard.edgeWorlds(), Portals.rows());
    }

    /** As {@link #build(ServerPlayer)}, with the linked worlds and portal rows given (GameTests). */
    public static MapData build(ServerPlayer player, List<MapLayout.World> worlds, Collection<PortalRow> portalRows) {
        String here = TeleportConfig.serverName();
        MapLayout.Result grid = MapLayout.layout(worlds, TeleportConfig.getOr(TeleportConfig.MAP_FIRST_SERVER, ""));
        Set<String> hidden = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        hidden.addAll(TeleportConfig.getOr(TeleportConfig.MAP_HIDDEN_SERVERS, List.<String>of()));
        Map<String, MapLayout.Cell> cells = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        grid.cells().forEach(c -> cells.put(c.server(), c));
        Map<String, Integer> sizes = new HashMap<>();
        worlds.forEach(w -> sizes.put(w.server(), Math.max(0, w.size())));

        // Warps this player can use, by the server they are on.
        Map<String, List<String>> warpsOn = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Warp w : TeleportActions.usableWarps(player)) {
            warpsOn.computeIfAbsent(w.position().server(), k -> new ArrayList<>()).add(w.name());
        }
        // Portals: from the server a portal stands on to each server it sends players to.
        Map<String, PortalRow> rowsById = new HashMap<>();
        portalRows.forEach(r -> rowsById.put(r.id(), r));
        TeleportData data = TeleportData.get(player.server);
        Set<List<String>> portalPairs = new LinkedHashSet<>();
        for (PortalRow r : portalRows) {
            for (PortalDestination d : r.destinations()) {
                String to = destinationServer(r, d, data, rowsById);
                if (to != null && !to.isEmpty() && !to.equalsIgnoreCase(r.server())) {
                    portalPairs.add(List.of(r.server(), to));
                }
            }
        }

        // Every server the network knows, each under one spelling (the grid's, else the first met), so names from
        // different places that differ only in case are one server (audit).
        Map<String, String> canon = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        cells.values().forEach(c -> canon.putIfAbsent(c.server(), c.server()));
        canon.putIfAbsent(here, here);
        PlayerDirectory.servers().forEach(n -> canon.putIfAbsent(n, n));
        warpsOn.keySet().forEach(n -> canon.putIfAbsent(n, n));
        portalPairs.forEach(p -> p.forEach(n -> canon.putIfAbsent(n, n)));
        java.util.function.UnaryOperator<String> c = n -> canon.getOrDefault(n, n);
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        canon.values().forEach(names::add);
        names.removeIf(n -> n.isEmpty() || (hidden.contains(n) && !cells.containsKey(n)));

        // Audit: a portal standing on a hidden server does not count as a way somewhere.
        Set<String> reachedByPortal = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        portalPairs.forEach(p -> {
            if (names.contains(p.get(0))) {
                reachedByPortal.add(p.get(1));
            }
        });
        Map<String, List<String>> others = PlayerDirectory.snapshot();
        List<MapData.Server> servers = new ArrayList<>();
        Map<String, MapData.Kind> kinds = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : names) {
            if (servers.size() >= MAX) {
                break;
            }
            MapLayout.Cell cell = cells.get(name);
            MapData.Kind kind = cell != null ? MapData.Kind.GRID
                    : warpsOn.containsKey(name) || reachedByPortal.contains(name) ? MapData.Kind.OFF : MapData.Kind.NONE;
            kinds.put(name, kind);
            boolean isHere = name.equalsIgnoreCase(here); // audit: the grid may spell this server's name in another case
            int online = isHere ? player.server.getPlayerList().getPlayerCount() : others.getOrDefault(name, List.of()).size();
            List<MapData.Crossing> crossings = new ArrayList<>();
            grid.crossings().getOrDefault(name, List.of()).forEach(x -> crossings.add(new MapData.Crossing(x.side().ordinal(), c.apply(x.to()))));
            List<String> warps = warpsOn.getOrDefault(name, List.of());
            servers.add(new MapData.Server(name, kind, cell == null ? 0 : cell.x(), cell == null ? 0 : cell.y(), sizes.getOrDefault(name, 0),
                    isHere || PlayerDirectory.isOnline(name), online, crossings, new ArrayList<>(warps.subList(0, Math.min(warps.size(), MAX_WARP_NAMES)))));
        }

        List<MapData.Bridge> bridges = new ArrayList<>();
        grid.bridges().stream().limit(MAX).forEach(b -> bridges.add(new MapData.Bridge(c.apply(b.a()), c.apply(b.b()))));
        List<MapData.Stub> stubs = new ArrayList<>();
        grid.stubs().stream().limit(MAX).forEach(s -> stubs.add(new MapData.Stub(c.apply(s.server()), s.side().ordinal(), c.apply(s.to()))));
        // A portal line touches a server off the grid; one from a server nothing reaches is left out (it has no lines).
        List<MapData.Portal> portals = new ArrayList<>();
        for (List<String> p : portalPairs) {
            MapData.Kind from = kinds.get(p.get(0)), to = kinds.get(p.get(1));
            if (from != null && to != null && from != MapData.Kind.NONE && to != MapData.Kind.NONE
                    && (from == MapData.Kind.OFF || to == MapData.Kind.OFF)) {
                if (portals.size() < MAX) {
                    portals.add(new MapData.Portal(c.apply(p.get(0)), c.apply(p.get(1))));
                }
            }
        }
        return new MapData(c.apply(here), grid.cols(), grid.rows(), servers, bridges, stubs, portals);
    }

    /** The server a portal destination sends players to, or null when it cannot be told. */
    @Nullable
    static String destinationServer(PortalRow row, PortalDestination d, TeleportData data, Map<String, PortalRow> rowsById) {
        return switch (d.kind()) {
            case WARP, PORTAL_WARP -> {
                Warp w = data.warp(d.target());
                yield w == null ? null : w.position().server();
            }
            case RANDOM -> d.target().isEmpty() ? row.server() : d.target();
            case POSITION -> d.position() == null ? null : d.position().server();
            case PORTAL -> {
                PortalRow other = rowsById.get(d.target());
                yield other == null ? null : other.server();
            }
            default -> null;
        };
    }
}
