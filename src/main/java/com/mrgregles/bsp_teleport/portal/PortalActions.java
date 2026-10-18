package com.mrgregles.bsp_teleport.portal;

import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.api.BspTeleportApi;
import com.mrgregles.bsp_teleport.api.Destination;
import com.mrgregles.bsp_teleport.block.PortalCoreBlock;
import com.mrgregles.bsp_teleport.block.PortalCoreBlockEntity;
import com.mrgregles.bsp_teleport.network.Messenger;
import com.mrgregles.bsp_teleport.data.PortalRow;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.data.Warp;
import com.mrgregles.bsp_teleport.network.PlayerDirectory;
import com.mrgregles.bsp_teleport.storage.Storage;
import net.minecraft.network.chat.Component;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** What an admin can do to a portal: shared by the commands (T54) and the core screen (T56). */
public final class PortalActions {
    private static final Pattern LINK_ID = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final double REACH = 8.0;

    private PortalActions() {
    }

    /** The Portal Core the admin is looking at, or null (with a message). */
    @Nullable
    public static PortalCoreBlockEntity lookedAt(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(REACH));
        HitResult hit = player.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit instanceof BlockHitResult block && player.level().getBlockEntity(block.getBlockPos()) instanceof PortalCoreBlockEntity core) {
            return core;
        }
        tell(player, "portal.look_at");
        return null;
    }

    // Destinations

    public static boolean setWarp(ServerPlayer player, PortalCoreBlockEntity core, String name) {
        Warp warp = TeleportData.get(player.server).warp(name);
        if (warp == null) {
            return fail(player, "warp.unknown", name);
        }
        return apply(player, core, PortalDestination.warp(warp.name(), warp.portal()));
    }

    public static boolean setRandom(ServerPlayer player, PortalCoreBlockEntity core, String server) {
        String target = server == null || server.isEmpty() || server.equals(TeleportConfig.serverName()) ? "" : server;
        if (!target.isEmpty() && !PlayerDirectory.servers().contains(target)) {
            return fail(player, "rtp.server_unknown", target);
        }
        return apply(player, core, PortalDestination.random(target));
    }

    public static boolean setHere(ServerPlayer player, PortalCoreBlockEntity core) {
        return apply(player, core, PortalDestination.position(Position.of(player)));
    }

    public static boolean setLink(ServerPlayer player, PortalCoreBlockEntity core, String id) {
        String link = id.toLowerCase(java.util.Locale.ROOT);
        if (!LINK_ID.matcher(link).matches()) {
            return fail(player, "portal.bad_link");
        }
        return apply(player, core, PortalDestination.link(link));
    }

    public static boolean clearDestination(ServerPlayer player, PortalCoreBlockEntity core) {
        return apply(player, core, PortalDestination.NONE);
    }

    private static boolean apply(ServerPlayer player, PortalCoreBlockEntity core, PortalDestination destination) {
        core.setDestination(destination);
        Portals.rescan(core);
        tell(player, "portal.set", core.name(), "destination " + destination.describe());
        core.describe(player, true);
        return true;
    }

    // Settings

    public static boolean setName(ServerPlayer player, PortalCoreBlockEntity core, String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 32) {
            return fail(player, "portal.bad_name");
        }
        core.setName(trimmed);
        Portals.rescan(core);
        return done(player, core, "name " + trimmed);
    }

    public static boolean setColour(ServerPlayer player, PortalCoreBlockEntity core, PortalColour colour) {
        core.setColour(colour);
        Portals.rescan(core);
        return done(player, core, "colour " + colour.getSerializedName());
    }

    public static boolean setArriveOnly(ServerPlayer player, PortalCoreBlockEntity core, boolean on) {
        core.setArriveOnly(on);
        Portals.rescan(core);
        return done(player, core, "arrive only " + (on ? "on" : "off"));
    }

    public static boolean setCloseWhenDown(ServerPlayer player, PortalCoreBlockEntity core, boolean on) {
        core.setCloseWhenDown(on);
        Portals.rescan(core);
        return done(player, core, "close when down " + (on ? "on" : "off"));
    }

    public static boolean setCooldown(ServerPlayer player, PortalCoreBlockEntity core, int seconds) {
        core.setCooldownSeconds(seconds);
        Portals.rescan(core);
        return done(player, core, "cooldown " + core.cooldownSeconds() + " s");
    }

    public static boolean setArrivalLine(ServerPlayer player, PortalCoreBlockEntity core, String line) {
        core.setArrivalLine(line.trim());
        Portals.rescan(core);
        return done(player, core, line.trim().isEmpty() ? "arrival line cleared" : "arrival line set");
    }

    // Open and close (D59)

    public static boolean setOpen(ServerPlayer player, PortalCoreBlockEntity core, boolean open) {
        core.setOpen(open);
        if (core.mode() != PortalCoreBlockEntity.Mode.MANUAL) {
            core.setMode(PortalCoreBlockEntity.Mode.MANUAL);
        }
        Portals.doorsChanged(core);
        return done(player, core, (open ? "open" : "closed") + " (manual)");
    }

    public static boolean setLocked(ServerPlayer player, PortalCoreBlockEntity core, boolean locked) {
        core.setLocked(locked);
        Portals.doorsChanged(core);
        Portals.publish(core);
        return done(player, core, locked ? "locked" : "unlocked");
    }

    public static boolean setManual(ServerPlayer player, PortalCoreBlockEntity core) {
        core.setMode(PortalCoreBlockEntity.Mode.MANUAL);
        Portals.doorsChanged(core);
        return done(player, core, "mode manual");
    }

    public static boolean setRedstone(ServerPlayer player, PortalCoreBlockEntity core, boolean openWhenPowered) {
        core.setRedstone(openWhenPowered);
        Portals.doorsChanged(core);
        return done(player, core, "mode redstone, " + (openWhenPowered ? "open" : "closed") + " when powered");
    }

    /** Times as HH:MM. */
    public static boolean setRealClock(ServerPlayer player, PortalCoreBlockEntity core, String openAt, String closeAt) {
        int open = parseTime(openAt), close = parseTime(closeAt);
        if (open < 0 || close < 0) {
            return fail(player, "portal.bad_time");
        }
        core.setRealClock(open, close);
        Portals.doorsChanged(core);
        return done(player, core, "mode real clock, open " + PortalCoreBlockEntity.hhmm(open) + " to " + PortalCoreBlockEntity.hhmm(close) + " (" + PortalCoreBlockEntity.zone().getId() + ")");
    }

    public static boolean setGameClock(ServerPlayer player, PortalCoreBlockEntity core, int openAt, int closeAt) {
        if (openAt < 0 || openAt > 24000 || closeAt < 0 || closeAt > 24000) {
            return fail(player, "portal.bad_game_time");
        }
        core.setGameClock(openAt % 24000, closeAt % 24000);
        Portals.doorsChanged(core);
        return done(player, core, "mode game clock, open " + openAt + " to " + closeAt);
    }

    public static boolean setDoorStyle(ServerPlayer player, PortalCoreBlockEntity core, PortalCoreBlockEntity.DoorStyle style) {
        core.setDoorStyle(style);
        Portals.doorsChanged(core);
        Portals.publish(core);
        return done(player, core, "doors " + style.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** Minutes of the day from HH:MM, or -1. */
    static int parseTime(String text) {
        String[] parts = text.trim().split(":");
        if (parts.length != 2) {
            return -1;
        }
        try {
            int h = Integer.parseInt(parts[0]), m = Integer.parseInt(parts[1]);
            return h < 0 || h > 23 || m < 0 || m > 59 ? -1 : h * 60 + m;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // Access rules (D86)

    public static boolean addRule(ServerPlayer player, PortalCoreBlockEntity core, com.mrgregles.bsp_teleport.access.AccessRule rule) {
        List<com.mrgregles.bsp_teleport.access.AccessRule> rules = core.rules();
        if (rule == null) {
            return fail(player, "access.rule_bad");
        }
        if (rules.size() >= com.mrgregles.bsp_teleport.access.AccessRule.MAX) {
            return fail(player, "access.rule_full", com.mrgregles.bsp_teleport.access.AccessRule.MAX);
        }
        rules.add(rule);
        core.setRules(rules);
        return done(player, core, "rule " + rule.describe());
    }

    public static boolean removeRule(ServerPlayer player, PortalCoreBlockEntity core, int index) {
        List<com.mrgregles.bsp_teleport.access.AccessRule> rules = core.rules();
        if (index < 0 || index >= rules.size()) {
            return false;
        }
        com.mrgregles.bsp_teleport.access.AccessRule removed = rules.remove(index);
        core.setRules(rules);
        return done(player, core, "rule removed: " + removed.describe());
    }

    /** Turns the core round: players then arrive on the other side (D56). */
    public static boolean flipFacing(ServerPlayer player, PortalCoreBlockEntity core) {
        ServerLevel level = (ServerLevel) core.getLevel();
        BlockState state = core.getBlockState();
        Direction facing = state.getValue(PortalCoreBlock.FACING).getOpposite();
        level.setBlock(core.getBlockPos(), state.setValue(PortalCoreBlock.FACING, facing), 3);
        Portals.rescan(core);
        return done(player, core, "facing " + facing.getName());
    }

    // Any portal on the network, by row (the panel and the name commands)

    /** open, close, lock or unlock a portal anywhere; a portal on another server is asked over Redis. */
    public static boolean command(ServerPlayer player, PortalRow row, String command) {
        if (row.server().equals(TeleportConfig.serverName())) {
            if (!Portals.applyCommand(player.server, row.id(), command)) {
                return fail(player, "portal.not_loaded", row.name());
            }
        } else if (!Messenger.portalCommand(row.server(), row.id(), command)) {
            return fail(player, "portal.no_network", row.name());
        }
        tell(player, "portal.set", row.name(), command + (row.server().equals(TeleportConfig.serverName()) ? "" : " (asked " + row.server() + ")"));
        return true;
    }

    /** Takes the admin to the portal: its exit spot when it has one, else the core itself. */
    public static boolean goTo(ServerPlayer player, PortalRow row) {
        Destination d = row.exit() != null ? row.exit()
                : new Destination(row.server(), row.world(), row.x() + 0.5, row.y() + 1, row.z() + 0.5, "");
        if (!BspTeleportApi.get().teleport(player, d)) {
            return fail(player, "failed");
        }
        return true;
    }

    public static void listPortals(ServerPlayer player) {
        List<PortalRow> rows = Portals.rows().stream().sorted((a, b) -> a.server().equals(b.server()) ? a.name().compareTo(b.name()) : a.server().compareTo(b.server())).toList();
        if (rows.isEmpty()) {
            tell(player, "portal.list_none");
            return;
        }
        String text = rows.stream().map(r -> r.name() + " [" + r.server() + " " + r.x() + "," + r.y() + "," + r.z() + "] " + r.destination().describe() + " (" + r.status().toLowerCase(java.util.Locale.ROOT) + ")")
                .collect(Collectors.joining("\n"));
        tell(player, "portal.list", rows.size(), "\n" + text);
    }

    // Portal warps (D55)

    public static boolean setPortalWarp(ServerPlayer player, String name) {
        if (!TeleportData.validName(name)) {
            return fail(player, "warp.bad_name");
        }
        TeleportData data = TeleportData.get(player.server);
        Warp existing = data.warp(name);
        if (existing != null && !existing.portal()) {
            return fail(player, "portalwarp.exists", existing.name());
        }
        Warp warp = new Warp(TeleportData.normalise(name), Position.of(player), 0, false, existing == null ? "" : existing.icon(), true);
        data.setWarp(warp);
        Storage.warpSaved(warp);
        Portals.destinationsChanged();
        tell(player, "portalwarp.set", warp.name());
        return true;
    }

    public static boolean delPortalWarp(ServerPlayer player, String name) {
        TeleportData data = TeleportData.get(player.server);
        Warp warp = data.warp(name);
        if (warp == null || !warp.portal()) {
            return fail(player, "portalwarp.unknown", name);
        }
        data.deleteWarp(warp.name());
        Storage.warpDeleted(warp.name());
        Portals.destinationsChanged();
        tell(player, "portalwarp.deleted", warp.name());
        return true;
    }

    public static void listPortalWarps(ServerPlayer player) {
        List<Warp> warps = TeleportData.get(player.server).warps().stream().filter(Warp::portal).toList();
        if (warps.isEmpty()) {
            tell(player, "portalwarp.none");
            return;
        }
        tell(player, "portalwarp.list", warps.stream().map(w -> w.name() + " (" + w.position().describe() + ")").collect(Collectors.joining(", ")));
    }

    private static boolean done(ServerPlayer player, PortalCoreBlockEntity core, String what) {
        tell(player, "portal.set", core.name(), what);
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
