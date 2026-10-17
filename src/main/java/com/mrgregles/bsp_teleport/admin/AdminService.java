package com.mrgregles.bsp_teleport.admin;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.api.BspTeleportApi;
import com.mrgregles.bsp_teleport.compat.BspCore;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.data.Warp;
import com.mrgregles.bsp_teleport.util.Icons;
import com.mrgregles.bsp_teleport.network.BandPackets;
import com.mrgregles.bsp_teleport.network.Messenger;
import com.mrgregles.bsp_teleport.network.PlayerDirectory;
import com.mrgregles.bsp_teleport.perm.Permissions;
import com.mrgregles.bsp_teleport.storage.Storage;
import com.mrgregles.bsp_teleport.teleport.AdminActions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The server side of the admin panel: builds what it shows, applies what it changes, moves who it says. */
public final class AdminService {
    private AdminService() {
    }

    public static boolean mayOpen(ServerPlayer player) {
        return Permissions.isAdmin(player) || BspCore.isModerator(player);
    }

    public static void open(ServerPlayer player) {
        if (!mayOpen(player)) {
            return;
        }
        BandPackets.sendAdmin(player, build(player));
    }

    public static void handle(ServerPlayer player, AdminAction action, String arg) {
        if (!mayOpen(player)) {
            return;
        }
        boolean readOnly = !Permissions.isAdmin(player);
        if (readOnly && action != AdminAction.OPEN) {
            return;
        }
        String[] parts = arg.split(";", -1);
        String a = parts.length > 0 ? parts[0] : "", b = parts.length > 1 ? parts[1] : "", c = parts.length > 2 ? parts[2] : "";
        switch (action) {
            case OPEN -> open(player);
            case SET_SETTING -> {
                setSetting(player, a, b);
                open(player);
            }
            case SET_WARP -> {
                if (TeleportData.validName(a)) {
                    Warp warp = new Warp(TeleportData.normalise(a), Position.of(player), parseInt(b, 0), Boolean.parseBoolean(c));
                    TeleportData.get(player.server).setWarp(warp);
                    Storage.warpSaved(warp);
                    tell(player, "warp.set", warp.name(), warp.restricted() ? Permissions.warpNode(warp.name()) : "-", warp.cost());
                } else {
                    tell(player, "warp.bad_name");
                }
                open(player);
            }
            case MOVE_WARP -> {
                editWarp(player, a, w -> w.withPosition(Position.of(player)));
                open(player);
            }
            case WARP_COST -> {
                editWarp(player, a, w -> w.withCost(Math.max(0, parseInt(b, w.cost()))));
                open(player);
            }
            case WARP_RESTRICT -> {
                editWarp(player, a, w -> w.withRestricted(Boolean.parseBoolean(b)));
                open(player);
            }
            case WARP_ICON -> {
                // The icon becomes what the admin holds; an empty hand clears it.
                String icon = Icons.heldItemId(player);
                editWarp(player, a, w -> w.withIcon(icon));
                open(player);
            }
            case RELOAD -> {
                TeleportConfig.reload(player.server);
                player.displayClientMessage(Component.translatable("message.bsp_teleport.admin.reloaded"), false);
                open(player);
            }
            case DEL_WARP -> {
                if (TeleportData.get(player.server).deleteWarp(a)) {
                    Storage.warpDeleted(TeleportData.normalise(a));
                    tell(player, "warp.deleted", TeleportData.normalise(a));
                }
                open(player);
            }
            case GO_WARP -> {
                Warp warp = TeleportData.get(player.server).warp(a);
                if (warp != null) {
                    BspTeleportApi.get().teleport(player, warp.position().toDestination(""));
                }
            }
            case GO_SERVER -> BspTeleportApi.get().sendToServer(player, a);
            case BRING_ALL -> AdminActions.bringAll(player, "*".equals(a) ? "all" : a);
            case SEND_ALL -> AdminActions.sendAll(player, a, b);
            case BRING_PLAYER -> AdminActions.bringPlayer(player, a);
            case GO_PLAYER -> AdminActions.goToPlayer(player, a);
            case GO_OFFLINE -> AdminActions.goToOffline(player, a);
        }
    }

    private interface WarpEdit {
        Warp apply(Warp warp);
    }

    private static void editWarp(ServerPlayer player, String name, WarpEdit edit) {
        TeleportData data = TeleportData.get(player.server);
        Warp warp = data.warp(name);
        if (warp == null) {
            tell(player, "warp.unknown", name);
            return;
        }
        Warp edited = edit.apply(warp);
        data.setWarp(edited);
        Storage.warpSaved(edited);
        tell(player, "warp.set", edited.name(), edited.restricted() ? Permissions.warpNode(edited.name()) : "-", edited.cost());
    }

    // Settings the panel may change. Credentials and names stay in the file.

    private record Entry(String id, ForgeConfigSpec.ConfigValue<?> value, String kind, int min, int max, int step) {
    }

    private static final List<Entry> SETTINGS = List.of(
            new Entry("warmup", TeleportConfig.WARMUP_SECONDS, "INT", 0, 120, 1),
            new Entry("cooldown", TeleportConfig.COOLDOWN_SECONDS, "INT", 0, 3600, 5),
            new Entry("cancelOnDamage", TeleportConfig.CANCEL_ON_DAMAGE, "BOOL", 0, 1, 1),
            new Entry("cancelOnMove", TeleportConfig.CANCEL_ON_MOVE, "BOOL", 0, 1, 1),
            new Entry("skipCooldownCoins", TeleportConfig.SKIP_COOLDOWN_COINS, "INT", 0, 100000, 10),
            new Entry("raidHomeDistance", TeleportConfig.RAID_HOME_DISTANCE, "INT", 0, 10000, 10),
            new Entry("raidRandomDistance", TeleportConfig.RAID_RANDOM_DISTANCE, "INT", 0, 10000, 10),
            new Entry("rtpEnabled", TeleportConfig.RTP_ENABLED, "BOOL", 0, 1, 1),
            new Entry("rtpMinRadius", TeleportConfig.RTP_MIN_RADIUS, "INT", 0, 1000000, 100),
            new Entry("rtpMaxRadius", TeleportConfig.RTP_MAX_RADIUS, "INT", 1, 1000000, 500),
            new Entry("requestSeconds", TeleportConfig.REQUEST_SECONDS, "INT", 5, 600, 5),
            new Entry("globalSpawnWarp", TeleportConfig.GLOBAL_SPAWN_WARP, "TEXT", 0, 16, 0),
            new Entry("rtpMinY", TeleportConfig.RTP_MIN_Y, "INT", -2048, 2048, 8),
            new Entry("rtpMaxY", TeleportConfig.RTP_MAX_Y, "INT", -2048, 2048, 8),
            new Entry("backRestrictedWorlds", TeleportConfig.BACK_RESTRICTED_WORLDS, "LIST", 0, 255, 0),
            new Entry("sounds", TeleportConfig.SOUNDS, "BOOL", 0, 1, 1));

    @SuppressWarnings("unchecked")
    private static void setSetting(ServerPlayer player, String id, String value) {
        for (Entry e : SETTINGS) {
            if (!e.id().equals(id)) {
                continue;
            }
            try {
                switch (e.kind()) {
                    case "INT" -> ((ForgeConfigSpec.ConfigValue<Integer>) e.value()).set(Math.max(e.min(), Math.min(e.max(), Integer.parseInt(value.trim()))));
                    case "BOOL" -> ((ForgeConfigSpec.ConfigValue<Boolean>) e.value()).set(Boolean.parseBoolean(value.trim()));
                    case "LIST" -> {
                        List<String> items = new ArrayList<>();
                        for (String part : value.split(",")) {
                            if (!part.trim().isEmpty()) {
                                items.add(part.trim());
                            }
                        }
                        ((ForgeConfigSpec.ConfigValue<List<? extends String>>) e.value()).set(items);
                    }
                    default -> ((ForgeConfigSpec.ConfigValue<String>) e.value()).set(value.trim());
                }
                TeleportConfig.SPEC.save();
                BSPTeleport.LOGGER.info("{} set {} to {}", player.getGameProfile().getName(), id, value.trim());
            } catch (RuntimeException ex) {
                player.displayClientMessage(Component.literal("Could not set " + id + ": " + ex.getMessage()), false);
            }
            return;
        }
    }

    public static AdminData build(ServerPlayer player) {
        List<AdminData.Setting> settings = new ArrayList<>();
        for (Entry e : SETTINGS) {
            Object raw = TeleportConfig.getOr((ForgeConfigSpec.ConfigValue<Object>) e.value(), "");
            String shown = raw instanceof List<?> list ? String.join(",", list.stream().map(String::valueOf).toList()) : String.valueOf(raw);
            settings.add(new AdminData.Setting(e.id(), shown, e.kind(), e.min(), e.max(), e.step()));
        }
        List<AdminData.WarpRow> warps = new ArrayList<>();
        for (Warp w : TeleportData.get(player.server).warps()) {
            warps.add(new AdminData.WarpRow(w.name(), w.position().server(), w.cost(), w.restricted(), w.icon()));
        }
        List<AdminData.ServerRow> servers = new ArrayList<>();
        List<String> here = new ArrayList<>();
        for (ServerPlayer p : player.server.getPlayerList().getPlayers()) {
            here.add(p.getGameProfile().getName());
        }
        servers.add(new AdminData.ServerRow(TeleportConfig.serverName(), true, true, here));
        for (Map.Entry<String, List<String>> e : PlayerDirectory.snapshot().entrySet()) {
            if (!e.getKey().equals(TeleportConfig.serverName())) {
                servers.add(new AdminData.ServerRow(e.getKey(), PlayerDirectory.isOnline(e.getKey()), false, e.getValue()));
            }
        }
        return new AdminData(TeleportConfig.serverName(), !Permissions.isAdmin(player), Storage.status(player.server), Messenger.status(), settings, warps, servers);
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void tell(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable("message.bsp_teleport." + key, args), false);
    }
}
