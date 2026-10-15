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

/** Picks a random safe spot around the world spawn, within the config's radii. */
public final class RandomTeleport {
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
        if (b == null) {
            return null;
        }
        double m = Math.min(margin, (Math.min(b[2] - b[0], b[3] - b[1])) / 2.0 - 1);
        return new double[] {b[0] + m, b[1] + m, b[2] - m, b[3] - m};
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
        // Region Guard's border, shrunk so nobody arrives next to its warning line (RG-T3).
        double[] box = shrunkBorder(level, BORDER_MARGIN);
        boolean ringMissesBox = box != null && !ringTouches(box, centre.getX(), centre.getZ(), min, max);
        for (int i = 0; i < tries; i++) {
            int x, z;
            if (ringMissesBox) {
                // The ring lies outside the border (small map, or spawn near an edge): draw anywhere inside the box.
                x = (int) Math.floor(box[0] + random.nextDouble() * (box[2] - box[0]));
                z = (int) Math.floor(box[1] + random.nextDouble() * (box[3] - box[1]));
            } else {
                double angle = random.nextDouble() * Math.PI * 2;
                double distance = min + random.nextDouble() * (max - min);
                x = centre.getX() + (int) Math.round(Math.cos(angle) * distance);
                z = centre.getZ() + (int) Math.round(Math.sin(angle) * distance);
                if (box != null && (x < box[0] || x > box[2] || z < box[1] || z > box[3])) {
                    continue;
                }
            }
            if (!level.getWorldBorder().isWithinBounds(new BlockPos(x, 0, z))) {
                continue;
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
