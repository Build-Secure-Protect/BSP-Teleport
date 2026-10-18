package com.mrgregles.bsp_teleport.block;

import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.data.PortalRow;
import com.mrgregles.bsp_teleport.portal.PortalColour;
import com.mrgregles.bsp_teleport.portal.PortalDestination;
import com.mrgregles.bsp_teleport.portal.PortalShape;
import com.mrgregles.bsp_teleport.portal.PortalStatus;
import com.mrgregles.bsp_teleport.portal.Portals;
import com.mrgregles.bsp_teleport.registry.ModBlocks;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A portal's settings (saved) and what the last scan found (not saved; rescanned on load). The settings grow with each
 * task: T54 adds the destination, T55 the modes and doors, T56 the screen that edits them.
 */
public class PortalCoreBlockEntity extends BlockEntity {
    /** How the doors are drawn (D60). */
    public enum DoorStyle { BLOCKS, RENDERED }

    /** What opens and closes the portal (D59). */
    public enum Mode { MANUAL, REDSTONE, REAL_CLOCK, GAME_CLOCK }

    /** A run of opening cells in one row, sorted along the axis; each run has its own pair of doors. */
    public record Segment(int y, List<BlockPos> cells) {
        public int size() {
            return cells.size();
        }

        /** Cells the left door (from the low end) covers at a progress. */
        public int leftCells(int progress) {
            return Math.min(progress, (size() + 1) / 2);
        }

        public int rightCells(int progress) {
            return Math.min(progress, size() / 2);
        }
    }

    /** Door animation ticks per cell (D66). */
    public static final int STEP_TICKS = 2;

    private String name = "Portal";
    private PortalColour colour = PortalColour.DEFAULT;
    private DoorStyle doorStyle = DoorStyle.BLOCKS;
    private boolean locked;
    private boolean open = true;
    private Mode mode = Mode.MANUAL;
    /** Redstone: the portal is open while powered (true) or closed while powered (false). */
    private boolean openWhenPowered = true;
    /** Real clock: minutes of the day; equal values mean always open. */
    private int openAtMinute, closeAtMinute;
    /** Game clock: world time 0 to 24000; equal values mean always open. */
    private int gameOpenAt, gameCloseAt;
    /** How far the doors have travelled, in cells per side; 0 is fully open. Saved, so a closed portal loads closed. */
    private int doorProgress;
    private boolean closeWhenDown = true;
    private int cooldownSeconds = 5;
    /** Reserved for the later Warp Gate (D53): a player-made portal with the admin tabs hidden. */
    private boolean playerOwned;
    private PortalDestination destination = PortalDestination.NONE;
    /** Players may arrive through this portal but not leave through it (D55). */
    private boolean arriveOnly;
    /** Shown to a player on arrival; empty for none (D61). */
    private String arrivalLine = "";
    /** When this core was first published; the two oldest cores own a link id (D55). 0 until then. */
    private long createdMs;

    @Nullable
    private PortalRow lastRow;

    // Door state, server side (transient) and what the client is sent
    private boolean powered;
    private List<Segment> segments = List.of();
    private int doorPrev;
    private long doorChangedAt;
    /** Client only: merged rows for the renderer, each {minU, maxU, minY, maxY}, plus the plane. */
    private List<int[]> clientRects = List.of();
    private boolean clientAlongX = true;
    private int clientFixed;
    @Nullable
    private AABB renderBox;

    @Nullable
    private PortalShape.Result shape;
    private Set<BlockPos> opening = Set.of();
    private PortalStatus status = PortalStatus.NO_FRAME;

    public PortalCoreBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.PORTAL_CORE_ENTITY.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel) {
            Portals.register(this);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level instanceof ServerLevel) {
            Portals.forget(this);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("Name", name);
        tag.putString("Colour", colour.getSerializedName());
        tag.putString("DoorStyle", doorStyle.name());
        tag.putBoolean("Locked", locked);
        tag.putBoolean("Open", open);
        tag.putBoolean("CloseWhenDown", closeWhenDown);
        tag.putInt("Cooldown", cooldownSeconds);
        tag.putBoolean("PlayerOwned", playerOwned);
        tag.put("Destination", destination.save());
        tag.putBoolean("ArriveOnly", arriveOnly);
        tag.putString("ArrivalLine", arrivalLine);
        tag.putLong("Created", createdMs);
        tag.putString("Mode", mode.name());
        tag.putBoolean("OpenWhenPowered", openWhenPowered);
        tag.putInt("OpenAt", openAtMinute);
        tag.putInt("CloseAt", closeAtMinute);
        tag.putInt("GameOpenAt", gameOpenAt);
        tag.putInt("GameCloseAt", gameCloseAt);
        tag.putInt("DoorProgress", doorProgress);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        name = tag.contains("Name") ? tag.getString("Name") : "Portal";
        colour = PortalColour.byName(tag.getString("Colour"));
        try {
            doorStyle = DoorStyle.valueOf(tag.getString("DoorStyle"));
        } catch (IllegalArgumentException e) {
            doorStyle = DoorStyle.BLOCKS;
        }
        locked = tag.getBoolean("Locked");
        open = !tag.contains("Open") || tag.getBoolean("Open");
        closeWhenDown = !tag.contains("CloseWhenDown") || tag.getBoolean("CloseWhenDown");
        cooldownSeconds = tag.contains("Cooldown") ? tag.getInt("Cooldown") : 5;
        playerOwned = tag.getBoolean("PlayerOwned");
        destination = tag.contains("Destination") ? PortalDestination.load(tag.getCompound("Destination")) : PortalDestination.NONE;
        arriveOnly = tag.getBoolean("ArriveOnly");
        arrivalLine = tag.getString("ArrivalLine");
        createdMs = tag.getLong("Created");
        try {
            mode = Mode.valueOf(tag.getString("Mode"));
        } catch (IllegalArgumentException e) {
            mode = Mode.MANUAL;
        }
        openWhenPowered = !tag.contains("OpenWhenPowered") || tag.getBoolean("OpenWhenPowered");
        openAtMinute = tag.getInt("OpenAt");
        closeAtMinute = tag.getInt("CloseAt");
        gameOpenAt = tag.getInt("GameOpenAt");
        gameCloseAt = tag.getInt("GameCloseAt");
        doorProgress = tag.getInt("DoorProgress");
        doorPrev = doorProgress;
    }

    // Client sync: the settings plus the door geometry and motion, sent on every door step.

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = saveWithoutMetadata();
        tag.putInt("DoorPrev", doorPrev);
        tag.putLong("DoorChangedAt", doorChangedAt);
        if (shape != null && status.frameOk()) {
            tag.putBoolean("AlongX", shape.axis() == PortalShape.Axis.X);
            tag.putInt("Fixed", shape.fixed());
            ListTag rects = new ListTag();
            for (int[] r : rects()) {
                rects.add(new net.minecraft.nbt.IntArrayTag(r));
            }
            tag.put("Rects", rects);
        }
        return tag;
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        load(tag);
        doorPrev = tag.getInt("DoorPrev");
        doorChangedAt = tag.getLong("DoorChangedAt");
        clientAlongX = tag.getBoolean("AlongX");
        clientFixed = tag.getInt("Fixed");
        List<int[]> rects = new ArrayList<>();
        for (Tag t : tag.getList("Rects", Tag.TAG_INT_ARRAY)) {
            rects.add(((net.minecraft.nbt.IntArrayTag) t).getAsIntArray());
        }
        clientRects = rects;
        renderBox = null;
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket packet) {
        if (packet.getTag() != null) {
            handleUpdateTag(packet.getTag());
        }
    }

    /** Rows with the same extent merged, for the renderer. */
    private List<int[]> rects() {
        List<int[]> out = new ArrayList<>();
        boolean alongX = shape != null && shape.axis() == PortalShape.Axis.X;
        for (Segment seg : segments) {
            int minU = alongX ? seg.cells().get(0).getX() : seg.cells().get(0).getZ();
            int maxU = alongX ? seg.cells().get(seg.size() - 1).getX() : seg.cells().get(seg.size() - 1).getZ();
            int[] last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && last[0] == minU && last[1] == maxU && last[3] == seg.y() - 1) {
                last[3] = seg.y();
            } else {
                out.add(new int[]{minU, maxU, seg.y(), seg.y()});
            }
        }
        return out;
    }

    public List<int[]> clientRects() {
        return clientRects;
    }

    public boolean clientAlongX() {
        return clientAlongX;
    }

    public int clientFixed() {
        return clientFixed;
    }

    /** The doors' travel as the client should draw it now, in cells per side. */
    public double visualProgress(float partialTick) {
        if (level == null || doorPrev == doorProgress) {
            return doorProgress;
        }
        double frac = Math.max(0, Math.min(1, (level.getGameTime() - doorChangedAt + partialTick) / (double) STEP_TICKS));
        return doorPrev + (doorProgress - doorPrev) * frac;
    }

    @Override
    public AABB getRenderBoundingBox() {
        if (renderBox == null) {
            AABB box = new AABB(worldPosition);
            for (int[] r : clientRects) {
                AABB rect = clientAlongX ? new AABB(r[0], r[2], clientFixed, r[1] + 1, r[3] + 1, clientFixed + 1) : new AABB(clientFixed, r[2], r[0], clientFixed + 1, r[3] + 1, r[1] + 1);
                box = box.minmax(rect);
            }
            renderBox = box;
        }
        return renderBox;
    }

    public String name() {
        return name;
    }

    public PortalColour colour() {
        return colour;
    }

    public DoorStyle doorStyle() {
        return doorStyle;
    }

    public boolean isLocked() {
        return locked;
    }

    public boolean isOpen() {
        return open;
    }

    public boolean closeWhenDown() {
        return closeWhenDown;
    }

    public int cooldownSeconds() {
        return cooldownSeconds;
    }

    public boolean playerOwned() {
        return playerOwned;
    }

    public PortalDestination destination() {
        return destination;
    }

    public boolean arriveOnly() {
        return arriveOnly;
    }

    public String arrivalLine() {
        return arrivalLine;
    }

    public long createdMs() {
        return createdMs;
    }

    @Nullable
    public PortalRow lastRow() {
        return lastRow;
    }

    public void setLastRow(@Nullable PortalRow row) {
        lastRow = row;
    }

    /** {@code server|world|x,y,z}: the key of this core's row in the shared table. */
    public String rowId() {
        return PortalRow.idOf(TeleportConfig.serverName(), level == null ? "" : level.dimension().location().toString(), worldPosition.getX(), worldPosition.getY(), worldPosition.getZ());
    }

    /** Sets the creation time once, on the first publish. */
    public void markCreated(long now) {
        if (createdMs == 0) {
            createdMs = now;
            setChanged();
        }
    }

    // Settings (the commands and, with T56, the screen call these, then Portals.rescan)

    public void setDestination(PortalDestination destination) {
        this.destination = destination;
        setChanged();
    }

    public void setName(String name) {
        this.name = name;
        setChanged();
    }

    public void setColour(PortalColour colour) {
        this.colour = colour;
        setChanged();
    }

    public void setArriveOnly(boolean arriveOnly) {
        this.arriveOnly = arriveOnly;
        setChanged();
    }

    public void setCloseWhenDown(boolean closeWhenDown) {
        this.closeWhenDown = closeWhenDown;
        setChanged();
    }

    public void setCooldownSeconds(int seconds) {
        this.cooldownSeconds = Math.max(0, seconds);
        setChanged();
    }

    public void setArrivalLine(String line) {
        this.arrivalLine = line;
        setChanged();
    }

    /** The status of the destination alone, asked only when the frame is fine. */
    public PortalStatus destinationStatus() {
        return Portals.destinationStatus(this);
    }

    public Mode mode() {
        return mode;
    }

    public boolean openWhenPowered() {
        return openWhenPowered;
    }

    public int openAtMinute() {
        return openAtMinute;
    }

    public int closeAtMinute() {
        return closeAtMinute;
    }

    public int gameOpenAt() {
        return gameOpenAt;
    }

    public int gameCloseAt() {
        return gameCloseAt;
    }

    public boolean powered() {
        return powered;
    }

    /** True when the redstone state changed. */
    public boolean setPowered(boolean powered) {
        boolean changed = this.powered != powered;
        this.powered = powered;
        return changed;
    }

    public void setOpen(boolean open) {
        this.open = open;
        setChanged();
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
        setChanged();
    }

    public void setDoorStyle(DoorStyle style) {
        this.doorStyle = style;
        setChanged();
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        setChanged();
    }

    public void setRedstone(boolean openWhenPowered) {
        this.mode = Mode.REDSTONE;
        this.openWhenPowered = openWhenPowered;
        setChanged();
    }

    public void setRealClock(int openAtMinute, int closeAtMinute) {
        this.mode = Mode.REAL_CLOCK;
        this.openAtMinute = openAtMinute;
        this.closeAtMinute = closeAtMinute;
        setChanged();
    }

    public void setGameClock(int openAt, int closeAt) {
        this.mode = Mode.GAME_CLOCK;
        this.gameOpenAt = openAt;
        this.gameCloseAt = closeAt;
        setChanged();
    }

    /** Whether the mode alone says open (D59); the lock and the destination watch are applied in {@link #wantsOpen}. */
    public boolean modeOpen() {
        return switch (mode) {
            case MANUAL -> open;
            case REDSTONE -> powered == openWhenPowered;
            case REAL_CLOCK -> inWindow(LocalTime.now(zone()).toSecondOfDay() / 60, openAtMinute, closeAtMinute);
            case GAME_CLOCK -> inWindow(level == null ? 0 : (int) (level.getDayTime() % 24000L), gameOpenAt, gameCloseAt);
        };
    }

    public static ZoneId zone() {
        String id = TeleportConfig.getOr(TeleportConfig.PORTAL_TIME_ZONE, "");
        try {
            return id.isEmpty() ? ZoneId.systemDefault() : ZoneId.of(id);
        } catch (RuntimeException e) {
            return ZoneId.systemDefault();
        }
    }

    /** Open from {@code open} until {@code close}, wrapping past midnight; equal bounds mean always open. */
    public static boolean inWindow(int now, int open, int close) {
        if (open == close) {
            return true;
        }
        return open < close ? now >= open && now < close : now >= open || now < close;
    }

    /** Whether the frame is fine and a destination is set: the portal then has doors and may light. */
    public boolean hasDoors() {
        return status.frameOk() && destination.isSet();
    }

    /** Whether the doors should be open: not locked, the mode says open, and the destination is not away while "close when down" is on (D57). */
    public boolean wantsOpen() {
        if (locked || !modeOpen()) {
            return false;
        }
        return status == PortalStatus.READY || !closeWhenDown;
    }

    /** Where the doors should be heading: 0 (open) or fully closed. */
    public int doorTarget() {
        return hasDoors() && !wantsOpen() ? maxProgress() : 0;
    }

    public int maxProgress() {
        int max = 0;
        for (Segment seg : segments) {
            max = Math.max(max, (seg.size() + 1) / 2);
        }
        return max;
    }

    public int doorProgress() {
        return doorProgress;
    }

    /** Moves the doors one step and tells the clients. */
    public void stepDoors(int progress) {
        doorPrev = doorProgress;
        doorProgress = progress;
        doorChangedAt = level == null ? 0 : level.getGameTime();
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** Server game time of the last door step; 0 before any. */
    public long doorChangedAtOrZero() {
        return doorChangedAt;
    }

    /** Doors fully open, fully closed, or on the way. */
    public boolean doorsMoving() {
        return doorProgress != doorTarget();
    }

    public List<Segment> segments() {
        return segments;
    }

    public void setSegments(List<Segment> segments) {
        this.segments = segments;
        if (doorProgress > maxProgress()) {
            doorProgress = maxProgress();
            doorPrev = doorProgress;
        }
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** Whether the field should be lit: doors fully open and the destination reachable (or down with "close when down" off, D57). */
    public boolean lit() {
        if (!hasDoors() || doorProgress != 0 || doorTarget() != 0) {
            return false;
        }
        return status == PortalStatus.READY || (status == PortalStatus.DOWN && !closeWhenDown);
    }

    /** One word for the doors: open, closed, opening, closing, locked; empty without doors. */
    public String doorWord() {
        if (!hasDoors()) {
            return "";
        }
        return locked ? "locked" : doorProgress == 0 && doorTarget() == 0 ? "open" : doorProgress == maxProgress() && doorTarget() != 0 ? "closed" : doorTarget() == 0 ? "opening" : "closing";
    }

    /** A line for admins: doors, mode and style. */
    public String describeDoors() {
        String doors = doorWord().isEmpty() ? "none" : doorWord();
        String m = switch (mode) {
            case MANUAL -> "manual";
            case REDSTONE -> "redstone, " + (openWhenPowered ? "open" : "closed") + " when powered" + (powered ? " (powered now)" : "");
            case REAL_CLOCK -> "real clock " + hhmm(openAtMinute) + " to " + hhmm(closeAtMinute) + " (" + zone().getId() + ")";
            case GAME_CLOCK -> "game clock " + gameOpenAt + " to " + gameCloseAt;
        };
        return doors + ", mode " + m + ", doors " + doorStyle.name().toLowerCase(java.util.Locale.ROOT) + (closeWhenDown ? ", close when down" : "");
    }

    public static String hhmm(int minute) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }

    @Nullable
    public PortalShape.Result shape() {
        return shape;
    }

    /** The opening cells of the last scan, in world coordinates; empty when the frame is not fine. */
    public Set<BlockPos> opening() {
        return opening;
    }

    public PortalStatus status() {
        return status;
    }

    /** Called by {@link Portals#rescan} with what it found. */
    public void setScan(PortalShape.Result shape, Set<BlockPos> opening, PortalStatus status) {
        this.shape = shape;
        this.opening = opening;
        this.status = status;
    }

    /** The status in chat: the full story for an admin, one line for anyone else (D63, until the screen exists). */
    public void describe(ServerPlayer player, boolean admin) {
        if (!admin) {
            player.displayClientMessage(Component.translatable("message.bsp_teleport.portal." + (lit() ? "open" : "closed"), name), true);
            return;
        }
        Component line = status == PortalStatus.TOO_BIG
                ? Component.translatable(status.key(), TeleportConfig.getOr(TeleportConfig.PORTAL_MAX_SIZE, 24), TeleportConfig.getOr(TeleportConfig.PORTAL_MAX_OPENING, 300))
                : Component.translatable(status.key());
        ChatFormatting colour = status == PortalStatus.READY ? ChatFormatting.GREEN : status.frameOk() ? ChatFormatting.GOLD : ChatFormatting.RED;
        player.sendSystemMessage(Component.translatable("message.bsp_teleport.portal.info", Component.literal(name).withStyle(ChatFormatting.AQUA), line.copy().withStyle(colour)));
        if (shape != null && status.frameOk()) {
            player.sendSystemMessage(Component.translatable("message.bsp_teleport.portal.size", shape.width(), shape.height(), opening.size()).withStyle(ChatFormatting.GRAY));
        }
        player.sendSystemMessage(Component.translatable("message.bsp_teleport.portal.destination", destination.describe() + (arriveOnly ? " (arrive only)" : "")).withStyle(ChatFormatting.GRAY));
        player.sendSystemMessage(Component.translatable("message.bsp_teleport.portal.doors", describeDoors()).withStyle(ChatFormatting.GRAY));
    }
}
