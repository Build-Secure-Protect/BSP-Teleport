package com.mrgregles.bsp_teleport.teleport;

import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.compat.BspCore;
import com.mrgregles.bsp_teleport.data.Position;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Picks a random safe spot around the world spawn, within the config's radii and the world's border. */
public final class RandomTeleport {
    /** T80: the most chunks one search may load or generate on the server thread. */
    static final int MAX_NEW_CHUNKS = 4;
    /** T83: draws per try to find a column inside both the ring and the border; plain arithmetic, no chunk is touched. */
    static final int DRAWS = 64;
    /** The vanilla world border's size when nobody has set it (a map 59,999,968 blocks across): not a real limit. */
    private static final double VANILLA_DEFAULT_BORDER = 5.9E7;

    private RandomTeleport() {
    }

    public static boolean enabled() {
        return TeleportConfig.getOr(TeleportConfig.RTP_ENABLED, true);
    }

    public static boolean allowedIn(ServerLevel level) {
        return worlds().contains(level.dimension().location().toString());
    }

    private static List<? extends String> worlds() {
        return TeleportConfig.getOr(TeleportConfig.RTP_WORLDS, List.of("minecraft:overworld"));
    }

    /** The world players from other servers land in: the first configured one that exists here. */
    @Nullable
    public static ServerLevel arrivalWorld(MinecraftServer server) {
        for (String id : worlds()) {
            ServerLevel level = TeleportService.levelOf(server, id);
            if (level != null) {
                return level;
            }
        }
        return null;
    }

    /**
     * The Y to stand at in this column, or -1 when there is none: a solid, allowed floor with two air blocks above it,
     * within the configured heights. Worlds with a ceiling are scanned downwards from the top; others use the heightmap.
     */
    private static int landingY(ServerLevel level, int x, int z, List<? extends String> unsafe) {
        int minY = Math.max(level.getMinBuildHeight() + 1, TeleportConfig.getOr(TeleportConfig.RTP_MIN_Y, -64));
        int maxY = Math.min(level.getMaxBuildHeight() - 2, TeleportConfig.getOr(TeleportConfig.RTP_MAX_Y, 320));
        if (!level.dimensionType().hasCeiling()) {
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            return y >= minY && y <= maxY && safeFloor(level, new BlockPos(x, y - 1, z), unsafe) ? y : -1;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, maxY, z);
        boolean airAbove = false, airAbove2 = false;
        for (int y = maxY; y >= minY - 1; y--) {
            pos.setY(y);
            boolean air = level.getBlockState(pos).isAir();
            if (!air && airAbove && airAbove2 && safeFloor(level, pos.immutable(), unsafe)) {
                return y + 1;
            }
            airAbove2 = airAbove;
            airAbove = air;
        }
        return -1;
    }

    private static boolean safeFloor(ServerLevel level, BlockPos ground, List<? extends String> unsafe) {
        BlockState floor = level.getBlockState(ground);
        if (!floor.getFluidState().isEmpty() || !floor.isSolid() || unsafe.contains(String.valueOf(ForgeRegistries.BLOCKS.getKey(floor.getBlock())))) {
            return false;
        }
        return level.getBlockState(ground.above()).isAir() && level.getBlockState(ground.above(2)).isAir();
    }

    /** Blocks kept clear of Region Guard's border edge: more than its default warning distance of 10. */
    static final int BORDER_MARGIN = 16;

    /** Region Guard's border for the world as {minX, minZ, maxX, maxZ} shrunk by {@code margin}, or null when there is none. */
    @Nullable
    static double[] shrunkBorder(ServerLevel level, int margin) {
        double[] b = com.mrgregles.bsp_teleport.compat.RegionGuard.border(level);
        return b == null ? null : shrink(b, margin);
    }

    /**
     * T83: the square spots must fall in, as {minX, minZ, maxX, maxZ}: Region Guard's border shrunk by {@link #BORDER_MARGIN},
     * else the vanilla world border shrunk the same way when an admin has set it; cut to the vanilla border either way.
     * Null when the world has neither.
     */
    @Nullable
    static double[] borderBox(ServerLevel level) {
        net.minecraft.world.level.border.WorldBorder vanilla = level.getWorldBorder();
        double[] edge = {vanilla.getMinX(), vanilla.getMinZ(), vanilla.getMaxX(), vanilla.getMaxZ()};
        double[] box = shrunkBorder(level, BORDER_MARGIN);
        if (box == null) {
            return vanilla.getSize() < VANILLA_DEFAULT_BORDER ? shrink(edge, BORDER_MARGIN) : null;
        }
        return new double[] {Math.max(box[0], edge[0]), Math.max(box[1], edge[1]), Math.min(box[2], edge[2]), Math.min(box[3], edge[3])};
    }

    private static double[] shrink(double[] b, int margin) {
        double m = Math.min(margin, (Math.min(b[2] - b[0], b[3] - b[1])) / 2.0 - 1);
        return new double[] {b[0] + m, b[1] + m, b[2] - m, b[3] - m};
    }

    /** How far the box's farthest corner is from {@code (cx, cz)}: with {@code useBorder} the ring reaches that far. Pure. */
    public static double farthest(double[] box, double cx, double cz) {
        double far = 0;
        for (double x : new double[] {box[0], box[2]}) {
            for (double z : new double[] {box[1], box[3]}) {
                far = Math.max(far, Math.hypot(x - cx, z - cz));
            }
        }
        return far;
    }

    /**
     * T83: one column inside both the ring {@code min..max} round {@code (cx, cz)} and the box, or null after {@link #DRAWS}
     * draws found none. It draws from whichever of the two is smaller, so a big ring round a small border (or the other way
     * round) does not spend the search's tries on spots outside it. A ring that misses the box altogether draws anywhere
     * in the box, as before. Pure, for the unit test.
     */
    @Nullable
    public static int[] pick(java.util.Random random, @Nullable double[] box, double cx, double cz, double min, double max) {
        if (box == null) {
            return ringPoint(random, cx, cz, min, max);
        }
        if (box[2] <= box[0] || box[3] <= box[1]) {
            // T83 audit: Region Guard's border lies wholly outside the vanilla one, so nowhere is inside both.
            return null;
        }
        if (!ringTouches(box, cx, cz, min, max)) {
            return boxPoint(random, box);
        }
        boolean fromBox = (box[2] - box[0]) * (box[3] - box[1]) <= Math.PI * (max * max - min * min);
        for (int d = 0; d < DRAWS; d++) {
            int[] p = fromBox ? boxPoint(random, box) : ringPoint(random, cx, cz, min, max);
            double dist = Math.hypot(p[0] - cx, p[1] - cz);
            if (p[0] >= box[0] && p[0] <= box[2] && p[1] >= box[1] && p[1] <= box[3] && dist >= min && dist <= max) {
                return p;
            }
        }
        return null;
    }

    private static int[] boxPoint(java.util.Random random, double[] box) {
        return new int[] {(int) Math.floor(box[0] + random.nextDouble() * (box[2] - box[0])), (int) Math.floor(box[1] + random.nextDouble() * (box[3] - box[1]))};
    }

    private static int[] ringPoint(java.util.Random random, double cx, double cz, double min, double max) {
        double angle = random.nextDouble() * Math.PI * 2;
        double distance = min + random.nextDouble() * (max - min);
        return new int[] {(int) Math.round(cx + Math.cos(angle) * distance), (int) Math.round(cz + Math.sin(angle) * distance)};
    }

    /** Whether the ring {@code min..max} around {@code (cx, cz)} overlaps the box at all. Pure, for the unit test. */
    public static boolean ringTouches(double[] box, double cx, double cz, double min, double max) {
        if (box[2] <= box[0] || box[3] <= box[1]) {
            return false;
        }
        // Nearest and farthest points of the box from the centre.
        double nx = Math.max(box[0], Math.min(cx, box[2])), nz = Math.max(box[1], Math.min(cz, box[3]));
        double near = Math.hypot(nx - cx, nz - cz);
        double far = 0;
        for (double x : new double[] {box[0], box[2]}) {
            for (double z : new double[] {box[1], box[3]}) {
                far = Math.max(far, Math.hypot(x - cx, z - cz));
            }
        }
        return far >= min && near <= max;
    }

    /** A safe landing position, or null when none was found in the configured number of tries. */
    @Nullable
    public static Position find(ServerLevel level, @Nullable ServerPlayer player) {
        return find(level, player, player == null ? null : player.getUUID());
    }

    /**
     * As above; {@code raidOwner} is the player the spot is for, who may be on another server (a remote {@code /rtp}
     * request): their totems being stolen are kept {@code raid.randomDistance} away.
     */
    @Nullable
    public static Position find(ServerLevel level, @Nullable ServerPlayer player, @Nullable java.util.UUID raidOwner) {
        int min = TeleportConfig.getOr(TeleportConfig.RTP_MIN_RADIUS, 500);
        int max = Math.max(min + 1, TeleportConfig.getOr(TeleportConfig.RTP_MAX_RADIUS, 5000));
        int tries = TeleportConfig.getOr(TeleportConfig.RTP_TRIES, 20);
        List<? extends String> unsafe = TeleportConfig.getOr(TeleportConfig.RTP_UNSAFE_BLOCKS, List.of());
        BlockPos centre = level.getSharedSpawnPos();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // The border, shrunk so nobody arrives next to its warning line (RG-T3).
        double[] box = borderBox(level);
        if (box != null && TeleportConfig.getOr(TeleportConfig.RTP_USE_BORDER, false)) {
            // T83: the whole border is the range; minRadius still keeps players off spawn.
            max = Math.max(min + 1, (int) Math.ceil(farthest(box, centre.getX(), centre.getZ())));
        }
        int newChunks = 0;
        for (int i = 0; i < tries; i++) {
            int[] at = pick(random, box, centre.getX(), centre.getZ(), min, max);
            if (at == null) {
                continue;
            }
            int x = at[0], z = at[1];
            if (!level.getWorldBorder().isWithinBounds(new BlockPos(x, 0, z))) {
                continue;
            }
            // T80: each try in an unloaded chunk loads (and may generate) it on the server thread; a search loads at most
            // MAX_NEW_CHUNKS of them, so a random teleport cannot freeze the server for seconds.
            if (!level.hasChunk(x >> 4, z >> 4)) {
                if (newChunks >= MAX_NEW_CHUNKS) {
                    continue;
                }
                newChunks++;
            }
            level.getChunk(x >> 4, z >> 4);
            int y = landingY(level, x, z, unsafe);
            if (y < 0) {
                continue;
            }
            if (!Rules.canTeleportTo(level, new BlockPos(x, y, z), player)) {
                continue;
            }
            if (raidOwner != null && BspCore.nearRaid(level.getServer(), raidOwner, "", level.dimension().location().toString(), x + 0.5, z + 0.5,
                    TeleportConfig.getOr(TeleportConfig.RAID_RANDOM_DISTANCE, 200))) {
                continue;
            }
            float yaw = player == null ? 0f : player.getYRot();
            float pitch = player == null ? 0f : player.getXRot();
            return new Position(TeleportConfig.serverName(), level.dimension().location().toString(), x + 0.5, y, z + 0.5, yaw, pitch);
        }
        return null;
    }
}
