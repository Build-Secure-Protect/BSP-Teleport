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
            Messenger.moveAll(server, here, by, by);
            tell(admin, "admin.moved_all_remote", server == null ? "every server" : server);
        }
        tell(admin, "admin.moved_all", moved);
        return true;
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
        Messenger.forceMove(name, here, admin.getGameProfile().getName(), "");
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
