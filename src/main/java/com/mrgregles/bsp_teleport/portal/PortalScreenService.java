package com.mrgregles.bsp_teleport.portal;

import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.admin.AdminService;
import com.mrgregles.bsp_teleport.block.PortalCoreBlock;
import com.mrgregles.bsp_teleport.block.PortalCoreBlockEntity;
import com.mrgregles.bsp_teleport.data.PortalRow;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.data.Warp;
import com.mrgregles.bsp_teleport.network.BandPackets;
import com.mrgregles.bsp_teleport.network.PlayerDirectory;
import com.mrgregles.bsp_teleport.perm.Permissions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** The server side of the core screen: builds what it shows and applies what it changes through {@link PortalActions}. */
public final class PortalScreenService {
    private static final double REACH_SQ = 64 * 64;

    private PortalScreenService() {
    }

    /** Admins and moderators (read-only) open it, as the admin panel (D47, D63). */
    public static boolean mayOpen(ServerPlayer player) {
        return AdminService.mayOpen(player);
    }

    public static void open(ServerPlayer player, PortalCoreBlockEntity core) {
        if (mayOpen(player)) {
            BandPackets.sendPortal(player, build(player, core));
        }
    }

    /** The core at the position, when it is loaded on the player's world and within reach. */
    @Nullable
    private static PortalCoreBlockEntity coreAt(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        if (!level.isLoaded(pos) || player.blockPosition().distSqr(pos) > REACH_SQ) {
            return null;
        }
        return level.getBlockEntity(pos) instanceof PortalCoreBlockEntity core ? core : null;
    }

    public static void handle(ServerPlayer player, BlockPos pos, PortalScreenAction action, String arg) {
        if (!mayOpen(player)) {
            return;
        }
        PortalCoreBlockEntity core = coreAt(player, pos);
        if (core == null) {
            return;
        }
        if (!Permissions.isAdmin(player) && action != PortalScreenAction.OPEN) {
            return;
        }
        String[] parts = arg.split(";", -1);
        String a = parts.length > 0 ? parts[0] : "", b = parts.length > 1 ? parts[1] : "";
        switch (action) {
            case OPEN -> {
            }
            case DEST_WARP -> PortalActions.setWarp(player, core, a);
            case DEST_RANDOM -> PortalActions.setRandom(player, core, a);
            case DEST_HERE -> PortalActions.setHere(player, core);
            case DEST_LINK -> PortalActions.setLink(player, core, a);
            case DEST_NONE -> PortalActions.clearDestination(player, core);
            case NAME -> PortalActions.setName(player, core, a);
            case COLOUR -> PortalActions.setColour(player, core, PortalColour.byName(a));
            case DOORS -> PortalActions.setDoorStyle(player, core, "rendered".equalsIgnoreCase(a) ? PortalCoreBlockEntity.DoorStyle.RENDERED : PortalCoreBlockEntity.DoorStyle.BLOCKS);
            case FLIP -> PortalActions.flipFacing(player, core);
            case ARRIVE_ONLY -> PortalActions.setArriveOnly(player, core, Boolean.parseBoolean(a));
            case ARRIVAL_LINE -> PortalActions.setArrivalLine(player, core, a);
            case MODE_MANUAL -> PortalActions.setManual(player, core);
            case MODE_REDSTONE -> PortalActions.setRedstone(player, core, Boolean.parseBoolean(a));
            case MODE_REAL -> PortalActions.setRealClock(player, core, a, b);
            case MODE_GAME -> PortalActions.setGameClock(player, core, parseInt(a), parseInt(b));
            case OPEN_NOW -> PortalActions.setOpen(player, core, true);
            case CLOSE_NOW -> PortalActions.setOpen(player, core, false);
            case LOCK -> PortalActions.setLocked(player, core, Boolean.parseBoolean(a));
            case CLOSE_WHEN_DOWN -> PortalActions.setCloseWhenDown(player, core, Boolean.parseBoolean(a));
            case COOLDOWN -> PortalActions.setCooldown(player, core, parseInt(a));
            case WARMUP -> PortalActions.setWarmup(player, core, parseInt(a));
            case RULE_ADD -> {
                String c = parts.length > 2 ? parts[2] : "", d = parts.length > 3 ? parts[3] : "";
                PortalActions.addRule(player, core, com.mrgregles.bsp_teleport.access.AccessRule.of(a, b, parseInt(c), Boolean.parseBoolean(d)));
            }
            case RULE_DEL -> PortalActions.removeRule(player, core, parseInt(a));
            case DEST_ADD -> PortalActions.addDestination(player, core, PortalActions.resolve(player, core, a, b), "");
            case DEST_DEL -> PortalActions.removeDestination(player, core, parseInt(a));
            case DEST_UP -> PortalActions.moveDestinationUp(player, core, parseInt(a));
            case DEST_LABEL -> PortalActions.labelDestination(player, core, parseInt(a), b);
        }
        open(player, core);
    }

    public static PortalScreenData build(ServerPlayer player, PortalCoreBlockEntity core) {
        ServerLevel level = (ServerLevel) core.getLevel();
        PortalShape.Result shape = core.shape();
        boolean frameOk = shape != null && core.status().frameOk();
        PortalDestination d = core.destination();
        String partner = "";
        if (d.isLink()) {
            for (PortalRow r : Portals.rows()) {
                if (r.linkId().equals(d.target()) && !r.id().equals(core.rowId())) {
                    partner = r.name() + " (" + r.server() + " " + r.x() + "," + r.y() + "," + r.z() + ")";
                    break;
                }
            }
        }
        List<PortalScreenData.WarpEntry> warps = new ArrayList<>();
        for (Warp w : TeleportData.get(player.server).warps()) {
            warps.add(new PortalScreenData.WarpEntry(w.name(), w.position().server(), w.portal()));
        }
        List<String> servers = new ArrayList<>();
        servers.add(TeleportConfig.serverName());
        for (String s : PlayerDirectory.servers()) {
            if (!servers.contains(s)) {
                servers.add(s);
            }
        }
        List<PortalScreenData.DestEntry> dests = new ArrayList<>();
        for (PortalDestination e : core.destinations()) {
            dests.add(new PortalScreenData.DestEntry(e.kind().name(), e.target(), e.label(), Portals.ownName(e).getString(), Portals.entryStatus(core, e).name()));
        }
        List<PortalScreenData.PortalEntry> portals = new ArrayList<>();
        for (PortalRow r : Portals.rows()) {
            if (!r.id().equals(core.rowId())) {
                portals.add(new PortalScreenData.PortalEntry(r.id(), r.name(), r.server()));
            }
        }
        portals.sort(java.util.Comparator.comparing((PortalScreenData.PortalEntry e) -> !e.server().equals(TeleportConfig.serverName())).thenComparing(PortalScreenData.PortalEntry::name, String.CASE_INSENSITIVE_ORDER));
        return new PortalScreenData(core.getBlockPos(), !Permissions.isAdmin(player), TeleportConfig.serverName(), level.dimension().location().toString(), core.name(),
                core.status().name(), Portals.maxSize(), Portals.maxOpening(), frameOk ? shape.width() : 0, frameOk ? shape.height() : 0, core.opening().size(),
                d.kind().name(), d.target(), d.describe(), partner, core.arriveOnly(), core.colour().getSerializedName(), core.doorStyle().name(),
                core.getBlockState().getValue(PortalCoreBlock.FACING).getName(), core.arrivalLine(), core.mode().name(), core.openWhenPowered(), core.powered(),
                core.openAtMinute(), core.closeAtMinute(), core.gameOpenAt(), core.gameCloseAt(), PortalCoreBlockEntity.zone().getId(), core.isLocked(),
                core.closeWhenDown(), core.cooldownSeconds(), core.doorWord(), core.lit(), warps, servers,
                com.mrgregles.bsp_teleport.access.AccessRule.serializeList(core.rules()), tokenFor(player, core), dests, portals, PortalActions.maxDestinations(), core.warmupSeconds());
    }

    /** The token that sells access to this portal (D86, TOKEN rule), or 0. */
    private static int tokenFor(ServerPlayer player, PortalCoreBlockEntity core) {
        com.mrgregles.bsp_teleport.access.TokenAssignment t = com.mrgregles.bsp_teleport.access.AccessService.tokenFor(player.server, com.mrgregles.bsp_teleport.access.AccessGrant.Kind.PORTAL, core.name());
        return t == null ? 0 : t.token();
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
