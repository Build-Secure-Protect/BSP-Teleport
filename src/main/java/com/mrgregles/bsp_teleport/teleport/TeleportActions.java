package com.mrgregles.bsp_teleport.teleport;

import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.compat.BspCore;
import com.mrgregles.bsp_teleport.data.Home;
import com.mrgregles.bsp_teleport.data.HomeLimits;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.data.Warp;
import com.mrgregles.bsp_teleport.network.Messenger;
import com.mrgregles.bsp_teleport.network.PlayerDirectory;
import com.mrgregles.bsp_teleport.perm.Permissions;
import com.mrgregles.bsp_teleport.storage.Storage;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.List;
import java.util.stream.Collectors;

/** What a player can do, shared by the commands and the band screen. Every message is a lang key. */
public final class TeleportActions {
    private TeleportActions() {
    }

    // Homes

    /** {@code /home [name]}: with one home and no name, goes there; with several and no name, lists them. */
    public static boolean home(ServerPlayer player, @Nullable String name) {
        TeleportData data = TeleportData.get(player.server);
        List<Home> homes = data.homesOf(player.getUUID());
        if (homes.isEmpty()) {
            return fail(player, "home.none");
        }
        if (name == null) {
            if (homes.size() > 1) {
                listHomes(player);
                return false;
            }
            name = homes.get(0).name();
        }
        Home home = data.home(player.getUUID(), name);
        if (home == null) {
            return fail(player, "home.unknown", name);
        }
        if (!HomeLimits.unlocked(player, data.homeIndex(player.getUUID(), name))) {
            return fail(player, "home.locked", home.name());
        }
        return Teleports.start(player, home.position().toDestination(""), Component.literal(home.name()), TeleportKind.HOME, null);
    }

    public static void listHomes(ServerPlayer player) {
        List<Home> homes = TeleportData.get(player.server).homesOf(player.getUUID());
        if (homes.isEmpty()) {
            fail(player, "home.none");
            return;
        }
        int slots = HomeLimits.slots(player);
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < homes.size(); i++) {
            if (i > 0) {
                list.append(", ");
            }
            list.append(homes.get(i).name());
            if (i >= slots) {
                list.append(" (locked)");
            }
        }
        tell(player, "home.list", homes.size(), slots, list.toString());
    }

    public static boolean setHome(ServerPlayer player, String name) {
        if (!TeleportData.validName(name)) {
            return fail(player, "home.bad_name");
        }
        TeleportData data = TeleportData.get(player.server);
        boolean exists = data.home(player.getUUID(), name) != null;
        int slots = HomeLimits.slots(player);
        if (!exists && data.homesOf(player.getUUID()).size() >= slots) {
            return fail(player, "home.full", slots);
        }
        if (!Rules.canSetHome(player.serverLevel(), player.blockPosition(), player)) {
            return fail(player, "home.blocked_here");
        }
        Home home = data.setHome(player.getUUID(), name, Position.of(player));
        Storage.homeSaved(player.getUUID(), home);
        tell(player, exists ? "home.updated" : "home.set", home.name());
        return true;
    }

    public static boolean delHome(ServerPlayer player, String name) {
        if (!TeleportData.get(player.server).deleteHome(player.getUUID(), name)) {
            return fail(player, "home.unknown", name);
        }
        Storage.homeDeleted(player.getUUID(), TeleportData.normalise(name));
        tell(player, "home.deleted", TeleportData.normalise(name));
        return true;
    }

    // Warps

    /** The warps this player may use. */
    public static List<Warp> usableWarps(ServerPlayer player) {
        return TeleportData.get(player.server).warps().stream()
                .filter(w -> !w.restricted() || Permissions.has(player, Permissions.warpNode(w.name())))
                .collect(Collectors.toList());
    }

    public static void listWarps(ServerPlayer player) {
        List<Warp> usable = usableWarps(player);
        if (usable.isEmpty()) {
            fail(player, "warp.none");
            return;
        }
        tell(player, "warp.list", usable.stream().map(w -> w.cost() > 0 ? w.name() + " (" + w.cost() + ")" : w.name()).collect(Collectors.joining(", ")));
    }

    public static boolean warp(ServerPlayer player, String name) {
        Warp warp = TeleportData.get(player.server).warp(name);
        if (warp == null) {
            return fail(player, "warp.unknown", name);
        }
        if (warp.restricted() && !Permissions.has(player, Permissions.warpNode(warp.name()))) {
            return fail(player, "warp.no_permission", warp.name());
        }
        if (warp.cost() > 0 && BspCore.coins(player) < warp.cost()) {
            return fail(player, "warp.cannot_afford", warp.cost());
        }
        return Teleports.start(player, warp.position().toDestination(""), Component.literal(warp.name()), TeleportKind.WARP, () -> {
            if (!BspCore.pay(player, warp.cost())) {
                tell(player, "warp.cannot_afford", warp.cost());
                return false;
            }
            if (warp.cost() > 0) {
                tell(player, "warp.paid", warp.cost());
            }
            return true;
        });
    }

    // Spawn, back, random

    /** The spawn this server's players go to: the global warp when configured, else this server's own. */
    @Nullable
    public static Position spawnPosition(ServerPlayer player) {
        String globalWarp = TeleportConfig.getOr(TeleportConfig.GLOBAL_SPAWN_WARP, "");
        if (!globalWarp.isEmpty()) {
            Warp warp = TeleportData.get(player.server).warp(globalWarp);
            return warp == null ? null : warp.position();
        }
        return TeleportData.get(player.server).spawn();
    }

    public static boolean spawn(ServerPlayer player) {
        String globalWarp = TeleportConfig.getOr(TeleportConfig.GLOBAL_SPAWN_WARP, "");
        Position spawn = spawnPosition(player);
        if (spawn == null) {
            return globalWarp.isEmpty() ? fail(player, "spawn.none") : fail(player, "spawn.global_missing", globalWarp);
        }
        return Teleports.start(player, spawn.toDestination(""), Component.translatable("message.bsp_teleport.spawn.name"), TeleportKind.SPAWN, null);
    }

    public static boolean back(ServerPlayer player) {
        Position back = Teleports.back(player);
        if (back == null) {
            return fail(player, "back.none");
        }
        if (!back.isLocal() && Messenger.enabled() && !PlayerDirectory.isOnline(back.server())) {
            return fail(player, "back.server_offline", back.server());
        }
        return Teleports.start(player, back.toDestination(""), Component.translatable("message.bsp_teleport.back.name"), TeleportKind.BACK, null);
    }

    /** {@code /rtp [server]}: here, or asked of another server over Redis. */
    public static boolean rtp(ServerPlayer player, @Nullable String server) {
        if (server != null && !server.isEmpty() && !server.equals(TeleportConfig.serverName())) {
            if (Teleports.isWaiting(player) || Teleports.cooldownLeft(player) > 0) {
                return Teleports.start(player, Position.of(player).toDestination(""), Component.empty(), TeleportKind.RANDOM, null);
            }
            if (!Messenger.enabled()) {
                return fail(player, "rtp.no_network");
            }
            if (!PlayerDirectory.servers().contains(server)) {
                return fail(player, "rtp.server_unknown", server);
            }
            Messenger.requestRandomSpot(player, server);
            tell(player, "rtp.asking", server);
            return true;
        }
        if (!RandomTeleport.enabled()) {
            return fail(player, "rtp.disabled");
        }
        if (!RandomTeleport.allowedIn(player.serverLevel())) {
            return fail(player, "rtp.wrong_world");
        }
        if (Teleports.isWaiting(player) || Teleports.cooldownLeft(player) > 0) {
            // Let the engine explain, without searching for a spot first.
            return Teleports.start(player, Position.of(player).toDestination(""), Component.empty(), TeleportKind.RANDOM, null);
        }
        Position spot = RandomTeleport.find(player.serverLevel(), player);
        if (spot == null) {
            return fail(player, "rtp.no_spot");
        }
        return Teleports.start(player, spot.toDestination(""), Component.translatable("message.bsp_teleport.rtp.name"), TeleportKind.RANDOM, null);
    }

    static void tell(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable("message.bsp_teleport." + key, args), false);
    }

    static boolean fail(ServerPlayer player, String key, Object... args) {
        tell(player, key, args);
        return false;
    }
}
