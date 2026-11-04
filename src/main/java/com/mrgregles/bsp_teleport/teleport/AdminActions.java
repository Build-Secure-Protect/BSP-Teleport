package com.mrgregles.bsp_teleport.teleport;

import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.api.BspTeleportApi;
import com.mrgregles.bsp_teleport.api.Destination;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.network.Messenger;
import com.mrgregles.bsp_teleport.network.PlayerDirectory;
import com.mrgregles.bsp_teleport.storage.Storage;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.UUID;

/** Forced moves an admin orders, shared by the commands and the admin panel. */
public final class AdminActions {
    private AdminActions() {
    }

    /**
     * B28: the admin's spot with this server named, for a move run on another server. {@code toDestination} leaves a
     * local spot's server empty, and the other server read empty as itself: the player went to these coordinates there.
     */
    private static Destination hereForOthers(ServerPlayer admin) {
        Position p = Position.of(admin);
        return new Destination(p.server(), p.world(), p.x(), p.y(), p.z(), p.yaw(), p.pitch(), "");
    }

    /** {@code /tpall}: "here" = this server, "all" = every server, else one server's name. Everyone comes to the admin. */
    public static boolean bringAll(ServerPlayer admin, String scope) {
        Destination here = Position.of(admin).toDestination("");
        String by = admin.getGameProfile().getName();
        int moved = 0;
        if (scope.equals("here") || scope.equals("all")) {
            for (ServerPlayer p : new ArrayList<>(admin.server.getPlayerList().getPlayers())) {
                if (p != admin && BspTeleportApi.get().teleport(p, here)) {
                    p.displayClientMessage(Component.translatable("message.bsp_teleport.admin.moved", by), false);
                    moved++;
                }
            }
        }
        if (!scope.equals("here") && TeleportConfig.singlePlayer()) {
            // D136: "every server" is just this world; one other server is refused.
            if (!scope.equals("all")) {
                return fail(admin, "single_player");
            }
            tell(admin, "admin.moved_all", moved);
            return true;
        }
        if (!scope.equals("here")) {
            String server = scope.equals("all") ? null : scope;
            if (!Messenger.enabled()) {
                return fail(admin, "admin.no_network");
            }
            if (server != null && !PlayerDirectory.isOnline(server)) {
                return fail(admin, "admin.server_offline", server);
            }
            Messenger.moveAll(server, hereForOthers(admin), by, by);
            tell(admin, "admin.moved_all_remote", server == null ? "every server" : server);
        }
        tell(admin, "admin.moved_all", moved);
        return true;
    }

    /**
     * {@code /tpall to <server> [x y z [world]]}: everyone on every server of the network goes to {@code to}; the admin
     * stays. With a {@code spot}, everyone lands on it, those already on {@code to} too. Without one they go to that
     * server's own spawn (its /setspawn point, from the shared database) and players already there stay put; with no
     * spawn known they land wherever that server puts them.
     */
    public static boolean sendEveryone(ServerPlayer admin, String to, @Nullable Vec spot) {
        if (TeleportConfig.singlePlayer()) {
            return fail(admin, "single_player");
        }
        if (!Messenger.enabled()) {
            return fail(admin, "admin.no_network");
        }
        String server = properServer(to);
        if (!PlayerDirectory.isOnline(server)) {
            return fail(admin, "admin.server_offline", server);
        }
        if (spot != null) {
            // Audit: a world this server does not have would move nobody without a word; other servers' worlds are not known here.
            if (server.equals(TeleportConfig.serverName()) && TeleportService.levelOf(admin.server, spot.world()) == null) {
                return fail(admin, "no_world");
            }
            moveEveryone(admin, new Position(server, spot.world(), spot.x(), spot.y(), spot.z(), 0f, 0f), null, true);
            return true;
        }
        Storage.spawnOf(server, spawn -> {
            if (spawn != null) {
                moveEveryone(admin, new Position(server, spawn.world(), spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch()), server, false);
                return;
            }
            int sent = 0;
            if (!server.equals(TeleportConfig.serverName())) {
                for (ServerPlayer p : new ArrayList<>(admin.server.getPlayerList().getPlayers())) {
                    if (p != admin && BspTeleportApi.get().sendToServer(p, server)) {
                        p.displayClientMessage(Component.translatable("message.bsp_teleport.admin.moved", admin.getGameProfile().getName()), false);
                        sent++;
                    }
                }
            }
            Messenger.sendAllEverywhere(server, admin.getGameProfile().getName());
            tell(admin, "admin.sent_everyone", server, sent);
        });
        return true;
    }

    /** A spot given on the command line: a world id and its coordinates. */
    public record Vec(String world, double x, double y, double z) {
    }

    /**
     * Moves everyone on the network to {@code where}, except the admin and, when {@code skip} is set, the players on
     * that server. A {@code typed} spot has no facing of its own, so each player keeps theirs.
     */
    private static void moveEveryone(ServerPlayer admin, Position where, @Nullable String skip, boolean typed) {
        String by = admin.getGameProfile().getName();
        boolean local = where.server().equals(TeleportConfig.serverName());
        Destination here = new Destination(local ? "" : where.server(), where.world(), where.x(), where.y(), where.z(),
                typed ? Float.NaN : where.yaw(), typed ? Float.NaN : where.pitch(), "");
        int moved = 0;
        if (skip == null || !local) {
            for (ServerPlayer p : new ArrayList<>(admin.server.getPlayerList().getPlayers())) {
                if (p != admin && BspTeleportApi.get().teleport(p, here)) {
                    p.displayClientMessage(Component.translatable("message.bsp_teleport.admin.moved", by), false);
                    moved++;
                }
            }
        }
        // Other servers get the spot with its server named, so they move their players across to it.
        Destination there = new Destination(where.server(), here.world(), here.x(), here.y(), here.z(), here.yaw(), here.pitch(), "");
        Messenger.moveAll(null, there, by, by, skip == null ? "" : skip);
        tell(admin, typed ? "admin.sent_everyone_spot" : "admin.sent_everyone", where.server(), moved,
                (int) Math.floor(where.x()) + " " + (int) Math.floor(where.y()) + " " + (int) Math.floor(where.z()));
    }

    /** The server's name as the network spells it, matched without case (this one too); as typed when unknown. */
    static String properServer(String typed) {
        if (TeleportConfig.serverName().equalsIgnoreCase(typed)) {
            return TeleportConfig.serverName();
        }
        for (String s : PlayerDirectory.servers()) {
            if (s.equalsIgnoreCase(typed)) {
                return s;
            }
        }
        return typed;
    }

    /** "nether", "end", "overworld" or any id without a namespace as a world id ("minecraft:the_nether"). */
    public static String worldId(@Nullable String typed) {
        if (typed == null || typed.isBlank()) {
            return "minecraft:overworld";
        }
        String t = typed.strip().toLowerCase(java.util.Locale.ROOT);
        return switch (t) {
            case "overworld" -> "minecraft:overworld";
            case "nether", "the_nether" -> "minecraft:the_nether";
            case "end", "the_end" -> "minecraft:the_end";
            default -> t.contains(":") ? t : "minecraft:" + t;
        };
    }

    /** Everyone on {@code from} is sent to {@code to} (landing wherever that server puts them). */
    public static boolean sendAll(ServerPlayer admin, String from, String to) {
        if (to.isEmpty() || to.equals(from)) {
            return false;
        }
        if (TeleportConfig.singlePlayer()) {
            return fail(admin, "single_player");
        }
        if (!PlayerDirectory.isOnline(to) && Messenger.enabled()) {
            return fail(admin, "admin.server_offline", to);
        }
        if (from.equals(TeleportConfig.serverName())) {
            int sent = 0;
            for (ServerPlayer p : new ArrayList<>(admin.server.getPlayerList().getPlayers())) {
                if (p != admin && BspTeleportApi.get().sendToServer(p, to)) {
                    p.displayClientMessage(Component.translatable("message.bsp_teleport.admin.moved", admin.getGameProfile().getName()), false);
                    sent++;
                }
            }
            tell(admin, "admin.sent_all", sent, to);
            return true;
        }
        if (!Messenger.enabled()) {
            return fail(admin, "admin.no_network");
        }
        Messenger.sendAllFrom(from, to, admin.getGameProfile().getName());
        tell(admin, "admin.sent_all_remote", from, to);
        return true;
    }

    public static boolean bringPlayer(ServerPlayer admin, String name) {
        Destination here = Position.of(admin).toDestination("");
        ServerPlayer target = Messenger.find(admin.server, name);
        if (target != null) {
            BspTeleportApi.get().teleport(target, here);
            target.displayClientMessage(Component.translatable("message.bsp_teleport.admin.moved", admin.getGameProfile().getName()), false);
            tell(admin, "admin.brought", target.getDisplayName());
            return true;
        }
        if (PlayerDirectory.serverOf(name) == null) {
            return fail(admin, "request.unknown_player", name);
        }
        Messenger.forceMove(name, hereForOthers(admin), admin.getGameProfile().getName(), "");
        tell(admin, "admin.brought", Component.literal(name));
        return true;
    }

    public static boolean goToPlayer(ServerPlayer admin, String name) {
        ServerPlayer target = Messenger.find(admin.server, name);
        if (target != null) {
            BspTeleportApi.get().teleport(admin, Position.of(target).toDestination(""));
            tell(admin, "teleported", target.getDisplayName());
            return true;
        }
        if (PlayerDirectory.serverOf(name) == null) {
            return fail(admin, "request.unknown_player", name);
        }
        Messenger.askPosition(admin, name);
        tell(admin, "admin.asking", name);
        return true;
    }

    public static boolean goToPosition(ServerPlayer admin, double x, double y, double z, @Nullable String world, @Nullable String server) {
        String w = world == null ? admin.level().dimension().location().toString() : world;
        String s = server == null || server.equals(TeleportConfig.serverName()) ? "" : server;
        if (!s.isEmpty() && Messenger.enabled() && !PlayerDirectory.isOnline(s)) {
            return fail(admin, "admin.server_offline", s);
        }
        if (!BspTeleportApi.get().teleport(admin, new Destination(s, w, x, y, z, ""))) {
            return fail(admin, "failed");
        }
        return true;
    }

    public static boolean goToOffline(ServerPlayer admin, String name) {
        if (Storage.enabled()) {
            Storage.lookup(name, found -> {
                if (found == null || found.offline() == null) {
                    fail(admin, "admin.no_offline", name);
                    return;
                }
                BspTeleportApi.get().teleport(admin, found.offline().toDestination(""));
                tell(admin, "teleported", Component.literal(found.name() + "'s last position"));
            });
            return true;
        }
        TeleportData data = TeleportData.get(admin.server);
        UUID uuid = data.findByName(name);
        Position where = uuid == null ? null : data.offline(uuid);
        if (where == null) {
            return fail(admin, "admin.no_offline", name);
        }
        BspTeleportApi.get().teleport(admin, where.toDestination(""));
        tell(admin, "teleported", Component.literal(name + "'s last position"));
        return true;
    }

    private static void tell(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable("message.bsp_teleport." + key, args), false);
    }

    private static boolean fail(ServerPlayer player, String key, Object... args) {
        tell(player, key, args);
        return false;
    }
}
