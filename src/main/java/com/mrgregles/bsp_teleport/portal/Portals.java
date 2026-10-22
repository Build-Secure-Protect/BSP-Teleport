package com.mrgregles.bsp_teleport.portal;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.api.Destination;
import com.mrgregles.bsp_teleport.api.event.TeleportStartEvent;
import com.mrgregles.bsp_teleport.block.PortalCoreBlock;
import com.mrgregles.bsp_teleport.block.PortalCoreBlockEntity;
import com.mrgregles.bsp_teleport.block.PortalDoorBlock;
import com.mrgregles.bsp_teleport.block.PortalFieldBlock;
import com.mrgregles.bsp_teleport.compat.BspCore;
import com.mrgregles.bsp_teleport.data.PortalRow;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.data.Warp;
import com.mrgregles.bsp_teleport.network.Messenger;
import com.mrgregles.bsp_teleport.network.PlayerDirectory;
import com.mrgregles.bsp_teleport.perm.Permissions;
import com.mrgregles.bsp_teleport.registry.ModBlocks;
import com.mrgregles.bsp_teleport.storage.Storage;
import com.mrgregles.bsp_teleport.teleport.RandomTeleport;
import com.mrgregles.bsp_teleport.teleport.Rules;
import com.mrgregles.bsp_teleport.teleport.TeleportKind;
import com.mrgregles.bsp_teleport.teleport.TeleportService;
import com.mrgregles.bsp_teleport.teleport.Teleports;
import com.mrgregles.bsp_teleport.util.Scheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps track of the loaded Portal Cores on this server and keeps each portal's blocks in step with its settings:
 * a rescan after any frame change places or removes the field cells (and, with T55, the doors). Also holds the
 * network's portal rows (D62), resolves links (D55), computes exits (D56), watches destinations (D57) and moves
 * players who step into a field (D61).
 */
public final class Portals {
    private static final Map<ResourceKey<Level>, Map<BlockPos, PortalCoreBlockEntity>> CORES = new HashMap<>();
    /** Every row on the network, by id, in network mode; refreshed on PORTALS_CHANGED and every five minutes. */
    private static final Map<String, PortalRow> ROWS = new HashMap<>();
    private static final Map<UUID, Long> TOLD = new HashMap<>();
    /** Per player, per core row id: when they may use that portal again (D61). */
    private static final Map<UUID, Map<String, Long>> COOLDOWNS = new HashMap<>();
    /** Where a player just landed on this server: a field there does not fire again until they walk away. */
    private static final Map<UUID, BlockPos> ARRIVED = new HashMap<>();
    /** Cores whose doors are moving or still have a cell to fill (D60); ticked every tick. */
    private static final Set<PortalCoreBlockEntity> MOTION = new LinkedHashSet<>();
    private static final int WATCH_TICKS = 100;

    private Portals() {
    }

    // Cores

    public static void register(PortalCoreBlockEntity core) {
        ServerLevel level = (ServerLevel) core.getLevel();
        CORES.computeIfAbsent(level.dimension(), k -> new HashMap<>()).put(core.getBlockPos().immutable(), core);
        // The chunk may still be loading: scan next tick.
        Scheduler.later(1, () -> {
            if (!core.isRemoved() && core.getLevel() != null) {
                rescan(core);
            }
        });
    }

    /** The core left memory (chunk unload or removal): its blocks and row are left alone. */
    public static void forget(PortalCoreBlockEntity core) {
        Map<BlockPos, PortalCoreBlockEntity> map = CORES.get(((ServerLevel) core.getLevel()).dimension());
        if (map != null) {
            map.remove(core.getBlockPos(), core);
        }
        MOTION.remove(core);
    }

    /** The core block was broken: its field, doors and row go with it; a linked partner turns unready (D62). */
    public static void coreRemoved(ServerLevel level, PortalCoreBlockEntity core) {
        for (BlockPos p : core.opening()) {
            clearCell(level, p);
        }
        forget(core);
        MOTION.remove(core);
        String id = core.rowId();
        ROWS.remove(id);
        TeleportData.get(level.getServer()).deletePortal(id);
        if (Storage.enabled()) {
            Storage.portalDeleted(id);
        }
        destinationsChanged();
    }

    public static Collection<PortalCoreBlockEntity> cores(ServerLevel level) {
        Map<BlockPos, PortalCoreBlockEntity> map = CORES.get(level.dimension());
        return map == null ? List.of() : new ArrayList<>(map.values());
    }

    private static List<PortalCoreBlockEntity> allCores() {
        List<PortalCoreBlockEntity> all = new ArrayList<>();
        for (Map<BlockPos, PortalCoreBlockEntity> map : CORES.values()) {
            all.addAll(map.values());
        }
        return all;
    }

    @Nullable
    public static PortalCoreBlockEntity coreFor(ServerLevel level, BlockPos openingCell) {
        for (PortalCoreBlockEntity core : cores(level)) {
            if (core.opening().contains(openingCell)) {
                return core;
            }
        }
        return null;
    }

    @Nullable
    private static PortalCoreBlockEntity coreById(String rowId) {
        for (PortalCoreBlockEntity core : allCores()) {
            if (core.rowId().equals(rowId)) {
                return core;
            }
        }
        return null;
    }

    /** A frame or core block was placed or removed: every portal that might include it is rescanned. */
    public static void changed(ServerLevel level, BlockPos pos) {
        Set<PortalCoreBlockEntity> todo = new LinkedHashSet<>();
        PortalShape.Pos p = new PortalShape.Pos(pos.getX(), pos.getY(), pos.getZ());
        for (PortalCoreBlockEntity core : cores(level)) {
            if (core.shape() != null && core.shape().touches(p)) {
                todo.add(core);
            }
        }
        walkForCores(level, pos, todo);
        todo.forEach(Portals::rescan);
    }

    /** Cores reachable from the position through frame blocks: the ones a new block has just joined. */
    private static void walkForCores(ServerLevel level, BlockPos start, Set<PortalCoreBlockEntity> out) {
        int max = maxSize();
        int limit = (max + 2) * (max + 2) * 2;
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty() && seen.size() <= limit) {
            BlockPos p = queue.poll();
            BlockState s = level.getBlockState(p);
            boolean frame = s.is(ModBlocks.PORTAL_FRAME.get());
            if (s.is(ModBlocks.PORTAL_CORE.get()) && level.getBlockEntity(p) instanceof PortalCoreBlockEntity core) {
                out.add(core);
            } else if (!frame && !p.equals(start)) {
                continue;
            }
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (Math.abs(n.getX() - start.getX()) > max + 1 || Math.abs(n.getY() - start.getY()) > max + 1 || Math.abs(n.getZ() - start.getZ()) > max + 1) {
                    continue;
                }
                if (seen.add(n)) {
                    BlockState ns = level.getBlockState(n);
                    if (ns.is(ModBlocks.PORTAL_FRAME.get()) || ns.is(ModBlocks.PORTAL_CORE.get())) {
                        queue.add(n);
                    }
                }
            }
        }
    }

    /** Scans the frame, works out the status, sets the door geometry and makes the blocks match. Then publishes the row. */
    public static void rescan(PortalCoreBlockEntity core) {
        ServerLevel level = (ServerLevel) core.getLevel();
        BlockPos at = core.getBlockPos();
        PortalShape.Result result = PortalShape.scan(lookup(level), new PortalShape.Pos(at.getX(), at.getY(), at.getZ()), maxSize(), maxOpening());
        Set<BlockPos> opening = new LinkedHashSet<>();
        if (result.status().frameOk()) {
            for (PortalShape.Pos p : result.opening()) {
                opening.add(new BlockPos(p.x(), p.y(), p.z()));
            }
        }
        for (BlockPos p : core.opening()) {
            if (!opening.contains(p)) {
                clearCell(level, p);
            }
        }
        core.setScan(result, opening, result.status());
        PortalStatus status = result.status().frameOk() ? destinationStatus(core) : result.status();
        core.setScan(result, opening, status);
        core.setPowered(level.hasNeighborSignal(at));
        core.setSegments(segmentsOf(opening, result.axis() == PortalShape.Axis.X));
        doorsChanged(core);
        publish(core);
    }

    /** Re-checks the destination only (rows or servers changed) and rescans when the status moved. */
    private static void refresh(PortalCoreBlockEntity core) {
        PortalShape.Result shape = core.shape();
        if (shape == null || !shape.status().frameOk()) {
            return;
        }
        if (destinationStatus(core) != core.status()) {
            rescan(core);
        } else {
            doorsChanged(core);
        }
    }

    // Doors (D59, D60)

    /** The opening split into runs per row, each sorted along the axis. */
    static List<PortalCoreBlockEntity.Segment> segmentsOf(Set<BlockPos> opening, boolean alongX) {
        Map<Integer, List<BlockPos>> rows = new HashMap<>();
        for (BlockPos p : opening) {
            rows.computeIfAbsent(p.getY(), k -> new ArrayList<>()).add(p);
        }
        List<Integer> ys = new ArrayList<>(rows.keySet());
        ys.sort(null);
        List<PortalCoreBlockEntity.Segment> out = new ArrayList<>();
        for (int y : ys) {
            List<BlockPos> row = rows.get(y);
            row.sort(Comparator.comparingInt(p -> alongX ? p.getX() : p.getZ()));
            List<BlockPos> run = new ArrayList<>();
            for (BlockPos p : row) {
                int u = alongX ? p.getX() : p.getZ();
                if (!run.isEmpty()) {
                    BlockPos last = run.get(run.size() - 1);
                    int lu = alongX ? last.getX() : last.getZ();
                    if (u != lu + 1) {
                        out.add(new PortalCoreBlockEntity.Segment(y, List.copyOf(run)));
                        run = new ArrayList<>();
                    }
                }
                run.add(p);
            }
            if (!run.isEmpty()) {
                out.add(new PortalCoreBlockEntity.Segment(y, List.copyOf(run)));
            }
        }
        return out;
    }

    /** Something that decides the doors changed (mode, lock, redstone, status): start moving if needed, and make the cells match now. */
    public static void doorsChanged(PortalCoreBlockEntity core) {
        if (core.isRemoved() || !(core.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!core.hasDoors() && core.doorProgress() != 0) {
            core.stepDoors(0);
        }
        boolean moving = core.doorsMoving();
        if (moving && !MOTION.contains(core)) {
            level.playSound(null, core.getBlockPos(), SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 0.6F, 0.7F);
        }
        if (!applyCells(core) || moving) {
            MOTION.add(core);
        } else {
            MOTION.remove(core);
        }
    }

    /** One tick of door travel for a core in motion. */
    private static void tickDoors(PortalCoreBlockEntity core) {
        if (core.isRemoved() || !(core.getLevel() instanceof ServerLevel level)) {
            MOTION.remove(core);
            return;
        }
        int target = core.doorTarget();
        int progress = core.doorProgress();
        if (progress != target) {
            long since = level.getGameTime() - core.doorChangedAtOrZero();
            if (since >= PortalCoreBlockEntity.STEP_TICKS) {
                progress += progress < target ? 1 : -1;
                core.stepDoors(progress);
                if (progress == target) {
                    level.playSound(null, core.getBlockPos(), target == 0 ? SoundEvents.PISTON_CONTRACT : SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 0.6F, target == 0 ? 0.8F : 0.6F);
                }
            }
        }
        boolean allPlaced = applyCells(core);
        if (allPlaced && core.doorProgress() == target) {
            MOTION.remove(core);
            publish(core);
        }
    }

    /** The door cells for the current travel: left doors from the low end, right doors from the high end, meeting in the middle of each run. */
    private static Map<BlockPos, PortalDoorBlock.Part> doorCells(PortalCoreBlockEntity core) {
        Map<BlockPos, PortalDoorBlock.Part> out = new HashMap<>();
        int p = core.doorProgress();
        boolean hidden = core.doorStyle() == PortalCoreBlockEntity.DoorStyle.RENDERED;
        for (PortalCoreBlockEntity.Segment seg : core.segments()) {
            int n = seg.size(), left = seg.leftCells(p), right = seg.rightCells(p);
            for (int i = 0; i < left; i++) {
                out.put(seg.cells().get(i), hidden ? PortalDoorBlock.Part.HIDDEN : i == left - 1 ? PortalDoorBlock.Part.EDGE_LEFT : PortalDoorBlock.Part.BODY);
            }
            for (int i = n - right; i < n; i++) {
                out.put(seg.cells().get(i), hidden ? PortalDoorBlock.Part.HIDDEN : i == n - right ? PortalDoorBlock.Part.EDGE_RIGHT : PortalDoorBlock.Part.BODY);
            }
        }
        return out;
    }

    /**
     * Writes every opening cell from the core's state: a door cell, the field, or air. A door cell a player stands in is
     * left for later (D60). Returns false while a cell is still waiting.
     */
    private static boolean applyCells(PortalCoreBlockEntity core) {
        ServerLevel level = (ServerLevel) core.getLevel();
        PortalShape.Result shape = core.shape();
        if (shape == null) {
            return true;
        }
        Direction.Axis axis = shape.axis() == PortalShape.Axis.X ? Direction.Axis.X : Direction.Axis.Z;
        Map<BlockPos, PortalDoorBlock.Part> doors = core.hasDoors() ? doorCells(core) : Map.of();
        boolean lit = core.lit();
        boolean allPlaced = true;
        for (BlockPos p : core.opening()) {
            PortalDoorBlock.Part part = doors.get(p);
            if (part != null) {
                BlockState want = ModBlocks.PORTAL_DOOR.get().defaultBlockState().setValue(PortalDoorBlock.AXIS, axis).setValue(PortalDoorBlock.PART, part);
                BlockState have = level.getBlockState(p);
                if (have.equals(want)) {
                    continue;
                }
                if (have.is(ModBlocks.PORTAL_FIELD.get())) {
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                }
                if (!level.getEntitiesOfClass(Player.class, new AABB(p)).isEmpty()) {
                    allPlaced = false;
                    continue;
                }
                if (have.isAir() || have.is(ModBlocks.PORTAL_DOOR.get())) {
                    level.setBlock(p, want, 3);
                }
            } else if (lit) {
                placeField(level, p, axis, core.colour());
            } else {
                clearCell(level, p);
            }
        }
        return allPlaced;
    }

    private static void placeField(ServerLevel level, BlockPos p, Direction.Axis axis, PortalColour colour) {
        BlockState want = ModBlocks.PORTAL_FIELD.get().defaultBlockState().setValue(PortalFieldBlock.AXIS, axis).setValue(PortalFieldBlock.COLOUR, colour);
        BlockState have = level.getBlockState(p);
        if (!have.equals(want) && (have.isAir() || have.is(ModBlocks.PORTAL_FIELD.get()) || have.is(ModBlocks.PORTAL_DOOR.get()))) {
            level.setBlock(p, want, 3);
        }
    }

    private static void clearCell(ServerLevel level, BlockPos p) {
        BlockState have = level.getBlockState(p);
        if (have.is(ModBlocks.PORTAL_FIELD.get()) || have.is(ModBlocks.PORTAL_DOOR.get())) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static PortalShape.Lookup lookup(ServerLevel level) {
        return (x, y, z) -> {
            BlockState s = level.getBlockState(new BlockPos(x, y, z));
            if (s.is(ModBlocks.PORTAL_FRAME.get())) {
                return PortalShape.Cell.FRAME;
            }
            if (s.is(ModBlocks.PORTAL_CORE.get())) {
                return PortalShape.Cell.CORE;
            }
            if (s.is(ModBlocks.PORTAL_FIELD.get())) {
                return PortalShape.Cell.FIELD;
            }
            if (s.is(ModBlocks.PORTAL_DOOR.get())) {
                return PortalShape.Cell.DOOR;
            }
            return s.isAir() ? PortalShape.Cell.AIR : PortalShape.Cell.OTHER;
        };
    }

    // Rows (D62)

    /** Every row this server knows: the network's in network mode, this world's in local mode. */
    public static Collection<PortalRow> rows() {
        if (Storage.enabled()) {
            return new ArrayList<>(ROWS.values());
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? List.of() : new ArrayList<>(TeleportData.get(server).portals());
    }

    /** The shared table was re-read. */
    public static void replaceRows(List<PortalRow> rows) {
        ROWS.clear();
        for (PortalRow r : rows) {
            ROWS.put(r.id(), r);
        }
        // This server's own cores are the truth for their rows; a stale read must not undo a change still being written.
        for (PortalCoreBlockEntity core : allCores()) {
            PortalRow mine = core.lastRow();
            if (mine != null) {
                ROWS.put(mine.id(), mine);
            }
        }
        destinationsChanged();
    }

    /** Something a destination depends on changed: every core re-checks its status. */
    public static void destinationsChanged() {
        for (PortalCoreBlockEntity core : allCores()) {
            refresh(core);
        }
    }

    /** Writes the core's row when it differs from the last one written. */
    public static void publish(PortalCoreBlockEntity core) {
        ServerLevel level = (ServerLevel) core.getLevel();
        core.markCreated(System.currentTimeMillis());
        PortalRow row = rowOf(core);
        if (row.sameAs(core.lastRow())) {
            return;
        }
        core.setLastRow(row);
        TeleportData.get(level.getServer()).setPortal(row);
        ROWS.put(row.id(), row);
        if (Storage.enabled()) {
            Storage.portalSaved(row);
        }
        destinationsChanged();
    }

    private static PortalRow rowOf(PortalCoreBlockEntity core) {
        ServerLevel level = (ServerLevel) core.getLevel();
        BlockPos p = core.getBlockPos();
        PortalShape.Result shape = core.shape();
        boolean frameOk = shape != null && shape.status().frameOk();
        return new PortalRow(core.rowId(), TeleportConfig.serverName(), level.dimension().location().toString(), p.getX(), p.getY(), p.getZ(), core.name(),
                core.destinations(), core.arriveOnly(), frameOk ? exitOf(core) : null, frameOk, core.colour().getSerializedName(), core.status().name(), core.doorWord(), core.createdMs(), core.warmupSeconds());
    }

    /** Where a linked partner lands its players (D56): one block in front of the opening on the core's facing side, feet on the lowest row, facing away. */
    @Nullable
    static Destination exitOf(PortalCoreBlockEntity core) {
        PortalShape.Result shape = core.shape();
        if (shape == null || !shape.status().frameOk() || core.opening().isEmpty()) {
            return null;
        }
        boolean alongX = shape.axis() == PortalShape.Axis.X;
        int minU = Integer.MAX_VALUE, maxU = Integer.MIN_VALUE, minY = Integer.MAX_VALUE;
        for (BlockPos p : core.opening()) {
            int u = alongX ? p.getX() : p.getZ();
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minY = Math.min(minY, p.getY());
        }
        Direction facing = core.getBlockState().getValue(PortalCoreBlock.FACING);
        Direction normal = facing.getAxis() != (alongX ? Direction.Axis.X : Direction.Axis.Z) ? facing : (alongX ? Direction.SOUTH : Direction.EAST);
        double centre = (minU + maxU + 1) / 2.0;
        double x = alongX ? centre : shape.fixed() + 0.5 + normal.getStepX();
        double z = alongX ? shape.fixed() + 0.5 + normal.getStepZ() : centre;
        ServerLevel level = (ServerLevel) core.getLevel();
        return new Destination(TeleportConfig.serverName(), level.dimension().location().toString(), x, minY, z, normal.toYRot(), 0f, "", -1);
    }

    // Destinations (D55, D57)

    /** The status of the destinations as a whole (D101): READY while any entry is usable, else the kindest reason among them. */
    public static PortalStatus destinationStatus(PortalCoreBlockEntity core) {
        convertLinks(core);
        List<PortalDestination> list = core.destinations();
        if (list.isEmpty()) {
            return PortalStatus.NO_DESTINATION;
        }
        PortalStatus best = null;
        for (PortalDestination d : list) {
            PortalStatus st = entryStatus(core, d);
            if (st == PortalStatus.READY) {
                return st;
            }
            if (best == null || st.ordinal() > best.ordinal()) {
                best = st;
            }
        }
        return best;
    }

    /** The status of one entry on its own. */
    public static PortalStatus entryStatus(PortalCoreBlockEntity core, PortalDestination d) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        switch (d.kind()) {
            case NONE:
                return PortalStatus.NO_DESTINATION;
            case WARP:
            case PORTAL_WARP: {
                Warp warp = server == null ? null : TeleportData.get(server).warp(d.target());
                if (warp == null) {
                    return PortalStatus.MISSING;
                }
                return PlayerDirectory.isOnline(warp.position().server()) ? PortalStatus.READY : PortalStatus.DOWN;
            }
            case RANDOM:
                if (d.target().isEmpty() || d.target().equals(TeleportConfig.serverName())) {
                    return RandomTeleport.enabled() ? PortalStatus.READY : PortalStatus.MISSING;
                }
                return PlayerDirectory.isOnline(d.target()) ? PortalStatus.READY : PortalStatus.DOWN;
            case POSITION:
                if (d.position() == null) {
                    return PortalStatus.MISSING;
                }
                return PlayerDirectory.isOnline(d.position().server()) ? PortalStatus.READY : PortalStatus.DOWN;
            case PORTAL: {
                PortalRow other = rowById(d.target());
                if (other == null) {
                    return PortalStatus.MISSING;
                }
                if (!other.frameOk() || other.exit() == null) {
                    return PortalStatus.WAITING;
                }
                return PlayerDirectory.isOnline(other.server()) ? PortalStatus.READY : PortalStatus.DOWN;
            }
            case BACK:
                // Each player's own spot (D110): the portal itself is always ready; the player's case is judged on the way in.
                return PortalStatus.READY;
            case LINK: {
                if (d.target().isEmpty()) {
                    return PortalStatus.MISSING;
                }
                List<PortalRow> owners = linkOwners(d.target(), core);
                boolean mine = owners.stream().anyMatch(r -> r.id().equals(core.rowId()));
                if (!mine) {
                    return PortalStatus.LINK_IN_USE;
                }
                PortalRow partner = owners.stream().filter(r -> !r.id().equals(core.rowId())).findFirst().orElse(null);
                if (partner == null || !partner.frameOk()) {
                    return PortalStatus.WAITING;
                }
                return PlayerDirectory.isOnline(partner.server()) ? PortalStatus.READY : PortalStatus.DOWN;
            }
            default:
                return PortalStatus.NO_DESTINATION;
        }
    }

    /**
     * D95: an old link entry becomes a Portal entry pointing at its partner as soon as the pair is visible. Each side
     * converts itself, so a pair split across servers joins once both have read the rows. An unpartnered link stays
     * a link (WAITING) until its partner appears.
     */
    private static void convertLinks(PortalCoreBlockEntity core) {
        List<PortalDestination> list = core.destinations();
        List<PortalDestination> out = null;
        for (int i = 0; i < list.size(); i++) {
            PortalDestination d = list.get(i);
            if (!d.isLink() || d.target().isEmpty()) {
                continue;
            }
            PortalRow partner = partnerOf(core, d.target());
            if (partner == null) {
                continue;
            }
            if (out == null) {
                out = new ArrayList<>(list);
            }
            out.set(i, PortalDestination.portal(partner.id()).withLabel(d.label()));
        }
        if (out != null) {
            core.setDestinations(out);
            BSPTeleport.LOGGER.info("Portal {} converted its link to a Portal destination", core.name());
        }
    }

    /** The two oldest rows sharing a link id (D55), this core's own row included even before it is published. */
    private static List<PortalRow> linkOwners(String linkId, PortalCoreBlockEntity core) {
        Map<String, PortalRow> byId = new HashMap<>();
        for (PortalRow r : rows()) {
            if (r.linkId().equals(linkId)) {
                byId.put(r.id(), r);
            }
        }
        PortalRow mine = core.lastRow();
        if (mine == null || !mine.linkId().equals(linkId)) {
            long created = core.createdMs() == 0 ? System.currentTimeMillis() : core.createdMs();
            mine = new PortalRow(core.rowId(), TeleportConfig.serverName(), "", 0, 0, 0, core.name(), core.destinations(), core.arriveOnly(), null, false, "", "", "", created, core.warmupSeconds());
        }
        byId.put(mine.id(), mine);
        List<PortalRow> sorted = new ArrayList<>(byId.values());
        sorted.sort(Comparator.comparingLong(PortalRow::createdMs).thenComparing(PortalRow::id));
        return sorted.size() > 2 ? sorted.subList(0, 2) : sorted;
    }

    @Nullable
    private static PortalRow partnerOf(PortalCoreBlockEntity core, String linkId) {
        return linkOwners(linkId, core).stream().filter(r -> !r.id().equals(core.rowId())).findFirst().orElse(null);
    }

    /** An entry's own name (D96): what the label falls back to. */
    public static Component ownName(PortalDestination d) {
        return switch (d.kind()) {
            case WARP, PORTAL_WARP -> Component.literal(d.target());
            case RANDOM -> d.target().isEmpty() || d.target().equals(TeleportConfig.serverName())
                    ? Component.translatable("message.bsp_teleport.portal.random_here")
                    : Component.translatable("message.bsp_teleport.portal.random_on", d.target());
            case POSITION -> d.position() == null ? Component.literal("?")
                    : Component.translatable("message.bsp_teleport.portal.position_at", (int) Math.floor(d.position().x()), (int) Math.floor(d.position().y()), (int) Math.floor(d.position().z()));
            case PORTAL -> {
                PortalRow other = rowById(d.target());
                yield other == null ? Component.translatable("message.bsp_teleport.portal.portal_gone") : Component.literal(other.name());
            }
            case LINK -> Component.literal("link " + d.target());
            case BACK -> Component.translatable("message.bsp_teleport.portal.back_name");
            default -> Component.literal("-");
        };
    }

    /** "warp market", or "3 destinations: Market, Arena, +1" (D103), for rows, the panel and the core's info line. */
    public static String summary(List<PortalDestination> list) {
        if (list.isEmpty()) {
            return "-";
        }
        if (list.size() == 1) {
            return list.get(0).describe();
        }
        StringBuilder sb = new StringBuilder(list.size() + " destinations: ");
        for (int i = 0; i < Math.min(2, list.size()); i++) {
            sb.append(i > 0 ? ", " : "").append(labelOf(list.get(i)).getString());
        }
        if (list.size() > 2) {
            sb.append(", +").append(list.size() - 2);
        }
        return sb.toString();
    }

    /** The label to show for an entry: the admin's, else the entry's own name. */
    public static Component labelOf(PortalDestination d) {
        return d.label().isEmpty() ? ownName(d) : Component.literal(d.label());
    }

    /** Which server an entry leads to, for the access check; empty when unknown (a remote random asks that server anyway). */
    private static String serverOf(PortalDestination d) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return switch (d.kind()) {
            case WARP, PORTAL_WARP -> {
                Warp warp = server == null ? null : TeleportData.get(server).warp(d.target());
                yield warp == null ? "" : warp.position().server();
            }
            case RANDOM -> d.target();
            case POSITION -> d.position() == null ? "" : d.position().server();
            case PORTAL -> {
                PortalRow other = rowById(d.target());
                yield other == null ? "" : other.server();
            }
            default -> "";
        };
    }

    // Walking through (D61, D97)

    /** A player stands in a field cell. */
    public static void touched(ServerLevel level, BlockPos pos, ServerPlayer player) {
        PortalCoreBlockEntity core = coreFor(level, pos);
        if (core == null || PortalChooser.isChoosing(player) || PortalWarmup.isWarming(player)) {
            return;
        }
        UUID id = player.getUUID();
        BlockPos landing = ARRIVED.get(id);
        if (landing != null && landing.distSqr(player.blockPosition()) <= 4) {
            return;
        }
        if (!core.lit()) {
            tell(player, "portal.not_ready");
            return;
        }
        if (core.arriveOnly()) {
            tell(player, "portal.arrive_only");
            return;
        }
        long now = System.currentTimeMillis();
        Long until = COOLDOWNS.computeIfAbsent(id, k -> new HashMap<>()).get(core.rowId());
        if (until != null && until > now) {
            tell(player, "portal.cooldown", (until - now + 999) / 1000);
            return;
        }
        long thief = BspCore.thiefBlockLeft(player);
        if (thief > 0) {
            tell(player, "blocked_thief", thief);
            return;
        }
        List<PortalDestination> list = core.destinations();
        if (list.size() <= 1) {
            travel(core, player, core.destination());
            return;
        }
        // Several entries (D97, D100): the portal's own rules first, then the usable set, then the chooser.
        com.mrgregles.bsp_teleport.access.AccessChecks.Result rules = com.mrgregles.bsp_teleport.access.AccessChecks.evaluate(player, core.rules(),
                com.mrgregles.bsp_teleport.access.AccessGrant.Kind.PORTAL, core.name());
        if (!rules.ok()) {
            if (!rules.refusal().getString().isEmpty()) {
                player.displayClientMessage(rules.refusal(), true);
            }
            hold(player, core, 3);
            return;
        }
        List<PortalChooser.Candidate> candidates = new ArrayList<>();
        List<PortalChoice.Option> options = new ArrayList<>();
        int usable = 0;
        PortalDestination only = null;
        Component firstReason = null;
        for (int i = 0; i < list.size(); i++) {
            PortalDestination d = list.get(i);
            PortalStatus st = entryStatus(core, d);
            PortalChoice.State state;
            Component detail;
            String server = serverOf(d);
            Component down = st == PortalStatus.READY ? null : Component.translatable(st.key());
            if (d.isBack()) {
                // The player's own spot (D111, D113): why it cannot be used, and its server for the access check.
                down = backRefusal(player);
                server = backServer(player);
            }
            if (down != null) {
                state = PortalChoice.State.DOWN;
                detail = down;
            } else if (!server.isEmpty() && !com.mrgregles.bsp_teleport.access.AccessService.mayEnter(player, server)) {
                state = PortalChoice.State.DENIED;
                com.mrgregles.bsp_teleport.access.TokenAssignment token = com.mrgregles.bsp_teleport.access.AccessService.tokenFor(player.server, com.mrgregles.bsp_teleport.access.AccessGrant.Kind.SERVER, server);
                detail = token == null ? Component.translatable("message.bsp_teleport.portal.needs_access", server)
                        : Component.translatable("message.bsp_teleport.portal.needs_access_token", server, "Access Token " + com.mrgregles.bsp_teleport.access.TokenAssignment.roman(token.token()));
                if (firstReason == null) {
                    firstReason = detail;
                }
            } else if (d.isBack()) {
                state = PortalChoice.State.OK;
                detail = server.isEmpty() ? Component.translatable("message.bsp_teleport.portal.back_detail")
                        : Component.translatable("message.bsp_teleport.portal.back_detail_on", server);
                usable++;
                only = d;
            } else {
                state = PortalChoice.State.OK;
                detail = server.isEmpty() ? ownName(d) : Component.translatable("message.bsp_teleport.portal.leads_to", ownName(d), server);
                usable++;
                only = d;
            }
            candidates.add(new PortalChooser.Candidate(i, d, state));
            options.add(new PortalChoice.Option(i, labelOf(d), detail, state, d.isBack()));
        }
        if (usable == 0) {
            if (firstReason != null) {
                player.displayClientMessage(firstReason, true);
                hold(player, core, 3);
            } else {
                tell(player, "portal.not_answering");
            }
            return;
        }
        if (usable == 1) {
            travel(core, player, only);
            return;
        }
        PortalChooser.start(player, core, candidates, new PortalChoice(Component.literal(core.name()), options));
    }

    /** Sends the player to one entry: the last checks of D61, the per-kind lookup, then the forced move. */
    static void travel(PortalCoreBlockEntity core, ServerPlayer player, PortalDestination d) {
        travel(core, player, d, null);
    }

    /** As {@link #travel(PortalCoreBlockEntity, ServerPlayer, PortalDestination)}; {@code hold} carries a chooser's hold into the warm-up (D120). */
    public static void travel(PortalCoreBlockEntity core, ServerPlayer player, PortalDestination d, @Nullable PortalWarmup.Hold hold) {
        switch (d.kind()) {
            case WARP, PORTAL_WARP -> {
                Warp warp = TeleportData.get(player.server).warp(d.target());
                if (warp == null) {
                    tell(player, "portal.not_answering");
                    return;
                }
                go(core, player, warp.position().toDestination(""), hold, false);
            }
            case POSITION -> {
                if (d.position() == null) {
                    tell(player, "portal.not_answering");
                    return;
                }
                go(core, player, d.position().toDestination(""), hold, false);
            }
            case PORTAL -> {
                PortalRow other = rowById(d.target());
                if (other == null || other.exit() == null || !PlayerDirectory.isOnline(other.server())) {
                    tell(player, "portal.not_answering");
                    return;
                }
                go(core, player, other.exit(), hold, false);
            }
            case LINK -> {
                PortalRow partner = partnerOf(core, d.target());
                if (partner == null || partner.exit() == null || !PlayerDirectory.isOnline(partner.server())) {
                    tell(player, "portal.not_answering");
                    return;
                }
                go(core, player, partner.exit(), hold, false);
            }
            case RANDOM -> {
                if (d.target().isEmpty() || d.target().equals(TeleportConfig.serverName())) {
                    ServerLevel world = RandomTeleport.arrivalWorld(player.server);
                    Position spot = RandomTeleport.enabled() && world != null ? RandomTeleport.find(world, player) : null;
                    if (spot == null) {
                        tell(player, "rtp.no_spot");
                        hold(player, core, 3);
                        return;
                    }
                    go(core, player, spot.toDestination(""), hold, false);
                } else {
                    if (!Messenger.enabled() || !PlayerDirectory.isOnline(d.target())) {
                        tell(player, "portal.not_answering");
                        return;
                    }
                    // One request, then wait for the answer (remoteRandomSpot) rather than asking again every tick.
                    hold(player, core, 5);
                    Messenger.requestRandomSpot(player, d.target(), core.rowId());
                    tell(player, "rtp.asking", d.target());
                }
            }
            case BACK -> {
                // D111 to D114: the player's own /back spot, with /back's landing checks on top of the portal's.
                Component why = backRefusal(player);
                if (why != null) {
                    player.displayClientMessage(why, true);
                    hold(player, core, 3);
                    return;
                }
                Destination back = Teleports.back(player).toDestination("");
                if (!back.crossServer() || back.server().equals(TeleportConfig.serverName())) {
                    ServerLevel level = TeleportService.levelOf(player.server, back.world());
                    if (level == null) {
                        tell(player, "no_world");
                        hold(player, core, 3);
                        return;
                    }
                    if (!Rules.canTeleportTo(level, BlockPos.containing(back.x(), back.y(), back.z()), player)) {
                        tell(player, "blocked_in");
                        hold(player, core, 3);
                        return;
                    }
                }
                go(core, player, back, hold, false);
            }
            default -> tell(player, "portal.not_ready");
        }
    }

    /** Why a Back entry cannot take this player anywhere (D113), or null when it can. */
    @Nullable
    public static Component backRefusal(ServerPlayer player) {
        Position back = Teleports.back(player);
        if (back == null || TeleportConfig.getOr(TeleportConfig.BACK_RESTRICTED_WORLDS, List.of()).contains(back.world())) {
            return Component.translatable("message.bsp_teleport.portal.back_none");
        }
        if (!back.isLocal() && Messenger.enabled() && !PlayerDirectory.isOnline(back.server())) {
            return Component.translatable("message.bsp_teleport.portal.back_down", back.server());
        }
        return null;
    }

    /** The server of the player's /back spot when it is another one, else empty. */
    private static String backServer(ServerPlayer player) {
        Position back = Teleports.back(player);
        return back == null || back.isLocal() ? "" : back.server();
    }

    /**
     * D112 (B9): where {@code /back} should return a player who stands here. Inside a portal's field that is the
     * portal's arrival spot in front of it, so {@code /back} and Back portals never land a player in the field they
     * left by; anywhere else, where they stand.
     */
    public static Position backSpot(ServerPlayer player) {
        PortalCoreBlockEntity core = fieldAt(player);
        if (core != null) {
            PortalShape.Result shape = core.shape();
            if (shape != null && shape.status().frameOk()) {
                return Position.fromDestination(exitOf(core));
            }
        }
        return Position.of(player);
    }

    /** D112 (B9): a landing inside a portal's field, by any route, is marked so the field does not fire until the player walks off. */
    public static void markIfInField(ServerPlayer player) {
        if (fieldAt(player) != null) {
            ARRIVED.put(player.getUUID(), player.blockPosition());
        }
    }

    @Nullable
    private static PortalCoreBlockEntity fieldAt(ServerPlayer player) {
        BlockPos feet = player.blockPosition();
        PortalCoreBlockEntity core = coreFor(player.serverLevel(), feet);
        return core != null ? core : coreFor(player.serverLevel(), feet.above());
    }

    /** A chooser ended without a move (D99): the cooldown and the arrival mark, so stepping out does not fire the field. */
    static void leftWithoutGoing(ServerPlayer player, PortalCoreBlockEntity core) {
        hold(player, core, core.cooldownSeconds());
        ARRIVED.put(player.getUUID(), player.blockPosition());
    }

    /** Another server answered a portal's random-spot request. */
    public static void remoteRandomSpot(ServerPlayer player, String coreId, Position spot) {
        PortalCoreBlockEntity core = coreById(coreId);
        if (core == null || player.hasDisconnected()) {
            return;
        }
        go(core, player, spot.toDestination(""), null, false);
    }

    /**
     * The last checks of D61 (raid distance, the start event, the portal's rules), then the warm-up when the portal has
     * one (D118), else the charge and the forced move. The warm-up calls back here with {@code warmed} set, so every
     * check runs again at the end and the coins are taken only then (D122).
     */
    public static void go(PortalCoreBlockEntity core, ServerPlayer player, Destination destination, @Nullable PortalWarmup.Hold hold, boolean warmed) {
        if (!com.mrgregles.bsp_teleport.access.AccessService.mayEnter(player, destination.server())) {
            com.mrgregles.bsp_teleport.access.AccessService.deny(player, com.mrgregles.bsp_teleport.access.AccessGrant.Kind.SERVER, destination.server());
            hold(player, core, 3);
            return;
        }
        if (BspCore.beingRaided(player)) {
            int distance = TeleportConfig.getOr(TeleportConfig.RAID_HOME_DISTANCE, 150);
            if (BspCore.nearRaid(player.server, player.getUUID(), destination.server(), destination.world(), destination.x(), destination.z(), distance)) {
                tell(player, "blocked_raid", distance);
                hold(player, core, 3);
                return;
            }
        }
        TeleportStartEvent event = new TeleportStartEvent(player, destination, TeleportKind.PORTAL.name());
        if (MinecraftForge.EVENT_BUS.post(event)) {
            if (!event.reason().isEmpty()) {
                player.displayClientMessage(Component.literal(event.reason()), true);
            }
            hold(player, core, 3);
            return;
        }
        com.mrgregles.bsp_teleport.access.AccessChecks.Result rules = com.mrgregles.bsp_teleport.access.AccessChecks.evaluate(player, core.rules(),
                com.mrgregles.bsp_teleport.access.AccessGrant.Kind.PORTAL, core.name());
        if (!rules.ok()) {
            if (!rules.refusal().getString().isEmpty()) {
                player.displayClientMessage(rules.refusal(), true);
            }
            hold(player, core, 3);
            return;
        }
        int warmup = core.warmupSeconds();
        if (!warmed && warmup > 0 && !Permissions.has(player, Permissions.BYPASS_WARMUP)) {
            PortalWarmup.start(player, core, destination, warmup, hold);
            return;
        }
        if (!com.mrgregles.bsp_teleport.access.AccessChecks.charge(player, rules)) {
            hold(player, core, 3);
            return;
        }
        hold(player, core, core.cooldownSeconds());
        if (!core.arrivalLine().isEmpty() && destination.message().isEmpty()) {
            destination = new Destination(destination.server(), destination.world(), destination.x(), destination.y(), destination.z(), destination.yaw(), destination.pitch(), core.arrivalLine(), destination.protect());
        }
        player.displayClientMessage(Component.translatable("message.bsp_teleport.portal.through", core.name()), true);
        if (!TeleportService.INSTANCE.teleport(player, destination)) {
            tell(player, "failed");
            return;
        }
        if (!destination.crossServer() || destination.server().equals(TeleportConfig.serverName())) {
            ARRIVED.put(player.getUUID(), BlockPos.containing(destination.x(), destination.y(), destination.z()));
        }
    }

    private static void hold(ServerPlayer player, PortalCoreBlockEntity core, int seconds) {
        COOLDOWNS.computeIfAbsent(player.getUUID(), k -> new HashMap<>()).put(core.rowId(), System.currentTimeMillis() + seconds * 1000L);
    }

    /** One action-bar line a second at most, so standing in a refusing field does not flood the player. */
    private static void tell(ServerPlayer player, String key, Object... args) {
        long now = player.level().getGameTime();
        Long last = TOLD.get(player.getUUID());
        if (last != null && now - last < 20) {
            return;
        }
        TOLD.put(player.getUUID(), now);
        player.displayClientMessage(Component.translatable("message.bsp_teleport." + key, args), true);
    }

    // Ticks and lifecycle

    /** The destination watch (D57) every five seconds, and the arrival marks that have been walked away from. */
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!MOTION.isEmpty()) {
            for (PortalCoreBlockEntity core : new ArrayList<>(MOTION)) {
                tickDoors(core);
            }
        }
        PortalChooser.tick(event.getServer());
        PortalWarmup.tick(event.getServer());
        if (event.getServer().getTickCount() % WATCH_TICKS != 0) {
            return;
        }
        destinationsChanged();
        ARRIVED.entrySet().removeIf(e -> {
            ServerPlayer p = event.getServer().getPlayerList().getPlayer(e.getKey());
            return p == null || e.getValue().distSqr(p.blockPosition()) > 4;
        });
    }

    // Commands on a portal anywhere on the network (the panel and /bsptp portal <name> ...)

    /** The core for a row id when it stands on this server, loading its chunk if needed; null elsewhere or when gone. */
    @Nullable
    public static PortalCoreBlockEntity localCore(MinecraftServer server, PortalRow row) {
        if (!row.server().equals(TeleportConfig.serverName())) {
            return null;
        }
        ServerLevel level = TeleportService.levelOf(server, row.world());
        if (level == null) {
            return null;
        }
        BlockPos pos = new BlockPos(row.x(), row.y(), row.z());
        level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        return level.getBlockEntity(pos) instanceof PortalCoreBlockEntity core ? core : null;
    }

    @Nullable
    public static PortalRow rowById(String id) {
        for (PortalRow r : rows()) {
            if (r.id().equals(id)) {
                return r;
            }
        }
        return null;
    }

    /** A row by name: this server's first, then any; null when none matches (case does not matter). */
    @Nullable
    public static PortalRow rowByName(String name) {
        PortalRow any = null;
        for (PortalRow r : rows()) {
            if (r.name().equalsIgnoreCase(name)) {
                if (r.server().equals(TeleportConfig.serverName())) {
                    return r;
                }
                if (any == null) {
                    any = r;
                }
            }
        }
        return any;
    }

    /** Applies open, close, lock or unlock to a core on this server; false when it is not here. */
    public static boolean applyCommand(MinecraftServer server, String rowId, String command) {
        PortalRow row = rowById(rowId);
        if (row == null) {
            return false;
        }
        PortalCoreBlockEntity core = localCore(server, row);
        if (core == null) {
            return false;
        }
        switch (command) {
            case "open" -> {
                core.setOpen(true);
                core.setMode(PortalCoreBlockEntity.Mode.MANUAL);
            }
            case "close" -> {
                core.setOpen(false);
                core.setMode(PortalCoreBlockEntity.Mode.MANUAL);
            }
            case "lock" -> core.setLocked(true);
            case "unlock" -> core.setLocked(false);
            default -> {
                return false;
            }
        }
        doorsChanged(core);
        publish(core);
        return true;
    }

    public static int maxSize() {
        return TeleportConfig.getOr(TeleportConfig.PORTAL_MAX_SIZE, 24);
    }

    public static int maxOpening() {
        return TeleportConfig.getOr(TeleportConfig.PORTAL_MAX_OPENING, 300);
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        CORES.clear();
        MOTION.clear();
        ROWS.clear();
        TOLD.clear();
        COOLDOWNS.clear();
        ARRIVED.clear();
    }
}
