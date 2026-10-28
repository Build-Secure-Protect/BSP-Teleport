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

    // The destination list (D94, D104)

    public static int maxDestinations() {
        return TeleportConfig.getOr(TeleportConfig.PORTAL_MAX_DESTINATIONS, PortalDestination.DEFAULT_MAX);
    }

    /** Resolves "kind;target" from the screen or a command into an entry, or null after telling the player why not. */
    @Nullable
    public static PortalDestination resolve(ServerPlayer player, PortalCoreBlockEntity core, String kind, String target) {
        switch (kind.toUpperCase(java.util.Locale.ROOT)) {
            case "WARP", "PORTAL_WARP", "PORTALWARP" -> {
                Warp warp = TeleportData.get(player.server).warp(target);
                if (warp == null) {
                    fail(player, "warp.unknown", target);
                    return null;
                }
                return PortalDestination.warp(warp.name(), warp.portal());
            }
            case "RANDOM" -> {
                String server = target == null || target.isEmpty() || target.equals(TeleportConfig.serverName()) ? "" : target;
                if (!server.isEmpty() && !PlayerDirectory.servers().contains(server)) {
                    fail(player, "rtp.server_unknown", server);
                    return null;
                }
                return PortalDestination.random(server);
            }
            case "POSITION", "HERE" -> {
                return PortalDestination.position(Position.of(player));
            }
            case "BACK" -> {
                return PortalDestination.back();
            }
            case "PORTAL" -> {
                PortalRow row = Portals.rowById(target);
                if (row == null) {
                    row = Portals.rowByName(target);
                }
                if (row == null) {
                    fail(player, "portal.unknown", target);
                    return null;
                }
                if (row.id().equals(core.rowId())) {
                    fail(player, "portal.dest_self");
                    return null;
                }
                return PortalDestination.portal(row.id());
            }
            default -> {
                fail(player, "portal.unknown", kind);
                return null;
            }
        }
    }

    public static boolean addDestination(ServerPlayer player, PortalCoreBlockEntity core, PortalDestination d, String label) {
        if (d == null) {
            return false;
        }
        int max = maxDestinations();
        if (core.destinations().size() >= max) {
            return fail(player, "portal.dest_full", core.name(), max);
        }
        if (!core.addDestination(d.withLabel(label == null ? "" : label), max)) {
            return fail(player, "portal.dest_duplicate", core.name());
        }
        Portals.rescan(core);
        tell(player, "portal.dest_added", core.name(), Portals.labelOf(d), core.destinations().size(), max);
        return true;
    }

    public static boolean removeDestination(ServerPlayer player, PortalCoreBlockEntity core, int index) {
        if (index < 0 || index >= core.destinations().size()) {
            return fail(player, "portal.dest_bad_index", core.destinations().size());
        }
        PortalDestination d = core.destinations().get(index);
        core.removeDestination(index);
        Portals.rescan(core);
        tell(player, "portal.dest_removed", core.name(), Portals.labelOf(d));
        return true;
    }

    public static boolean moveDestinationUp(ServerPlayer player, PortalCoreBlockEntity core, int index) {
        if (!core.moveDestinationUp(index)) {
            return false;
        }
        Portals.rescan(core);
        return true;
    }

    public static boolean labelDestination(ServerPlayer player, PortalCoreBlockEntity core, int index, String label) {
        if (index < 0 || index >= core.destinations().size()) {
            return fail(player, "portal.dest_bad_index", core.destinations().size());
        }
        String text = label == null ? "" : label.trim();
        if (text.length() > 32) {
            text = text.substring(0, 32);
        }
        core.setDestinationLabel(index, text);
        Portals.rescan(core);
        tell(player, "portal.dest_label", core.name(), index + 1, Portals.labelOf(core.destinations().get(index)));
        return true;
    }

    public static boolean clearDestinations(ServerPlayer player, PortalCoreBlockEntity core) {
        core.setDestinations(List.of());
        Portals.rescan(core);
        tell(player, "portal.dest_cleared", core.name());
        return true;
    }

    public static boolean listDestinations(ServerPlayer player, PortalCoreBlockEntity core) {
        List<PortalDestination> list = core.destinations();
        if (list.isEmpty()) {
            tell(player, "portal.destination", "-");
            return true;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            PortalDestination d = list.get(i);
            sb.append(i > 0 ? "; " : "").append(i + 1).append(". ").append(Portals.labelOf(d).getString()).append(" (").append(d.describe()).append(", ")
                    .append(Portals.entryStatus(core, d).name().toLowerCase(java.util.Locale.ROOT)).append(")");
        }
        tell(player, "portal.dest_list", core.name(), sb.toString());
        return true;
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
        if (Portals.nameTaken(trimmed, core)) {
            return fail(player, "portal.name_taken", trimmed);
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

    /** D147: deletes a portal row by name, for a server that will never run again; a live core here keeps its row. */
    public static boolean forget(ServerPlayer player, PortalRow row) {
        if (row.server().equalsIgnoreCase(TeleportConfig.serverName()) && Portals.localCore(player.server, row) != null) {
            return fail(player, "portal.forget_live", row.name());
        }
        Portals.forgetRow(player.server, row, "removed by " + player.getGameProfile().getName());
        Portals.destinationsChanged();
        tell(player, "portal.forgotten", row.name(), row.server());
        return true;
    }

    /** The portal's warm-up (D118), 0 to 30 s; 0 sends players through at once. */
    public static boolean setWarmup(ServerPlayer player, PortalCoreBlockEntity core, int seconds) {
        core.setWarmupSeconds(seconds);
        Portals.rescan(core);
        return done(player, core, core.warmupSeconds() == 0 ? "no warm-up" : "warm-up " + core.warmupSeconds() + " s");
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

    /** T82: the whole list of windows, "15:00-17:00, 03:00-05:00" (or "15:00 17:00"); touching ones are joined, up to four. */
    public static boolean setRealClock(ServerPlayer player, PortalCoreBlockEntity core, String text) {
        com.mrgregles.bsp_teleport.access.OpenHours hours = com.mrgregles.bsp_teleport.access.OpenHours.parse(text);
        if (hours == null) {
            return fail(player, "portal.bad_hours", com.mrgregles.bsp_teleport.access.OpenHours.MAX);
        }
        core.setRealClock(hours);
        Portals.doorsChanged(core);
        return done(player, core, "mode real clock, open " + hours.describe() + " (" + PortalCoreBlockEntity.zone().getId() + ")");
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

    // Access rules (D86)

    public static boolean addRule(ServerPlayer player, PortalCoreBlockEntity core, com.mrgregles.bsp_teleport.access.AccessRule rule) {
        List<com.mrgregles.bsp_teleport.access.AccessRule> rules = core.rules();
        if (rule == null) {
            return fail(player, "access.rule_bad");
        }
        List<com.mrgregles.bsp_teleport.access.AccessRule> next = com.mrgregles.bsp_teleport.access.AccessRule.add(rules, rule);
        if (next == null) {
            return refuseRule(player, rules, rule);
        }
        core.setRules(next);
        return done(player, core, "rule " + rule.describe());
    }

    /** Why {@link com.mrgregles.bsp_teleport.access.AccessRule#add} refused: too many windows once joined (D158), or a full list. */
    public static boolean refuseRule(ServerPlayer player, List<com.mrgregles.bsp_teleport.access.AccessRule> rules, com.mrgregles.bsp_teleport.access.AccessRule rule) {
        boolean joins = rule.kind() == com.mrgregles.bsp_teleport.access.AccessRule.Kind.TIME && rules.stream().anyMatch(r -> r.hours() != null);
        return joins ? fail(player, "portal.bad_hours", com.mrgregles.bsp_teleport.access.OpenHours.MAX)
                : fail(player, "access.rule_full", com.mrgregles.bsp_teleport.access.AccessRule.MAX);
    }

    public static boolean badHours(ServerPlayer player) {
        return fail(player, "portal.bad_hours", com.mrgregles.bsp_teleport.access.OpenHours.MAX);
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
