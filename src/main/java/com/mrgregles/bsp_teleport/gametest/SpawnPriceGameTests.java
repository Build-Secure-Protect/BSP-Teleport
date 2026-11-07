package com.mrgregles.bsp_teleport.gametest;

import com.mrgregles.bsp_core.api.BspBank;
import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.band.BandData;
import com.mrgregles.bsp_teleport.band.BandService;
import com.mrgregles.bsp_teleport.compat.BspCore;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.data.Warp;
import com.mrgregles.bsp_teleport.teleport.NetworkSpawn;
import com.mrgregles.bsp_teleport.teleport.TeleportActions;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * B47: /spawn and the band's Spawn pay the network spawn warp's price, and the band shows it; a server's own /setspawn
 * point stays free. B48: a warp's own pieces cost counts teleport store credit, as its PIECES rules already did. The
 * warm-up is set to 0 and the bank answers at once on one server, so each trip and charge is over on return.
 */
@GameTestHolder(BSPTeleport.MODID)
@PrefixGameTestTemplate(false)
public class SpawnPriceGameTests {
    private static final String EMPTY = "empty";

    private static ServerPlayer withPieces(GameTestHelper h, long pieces) {
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        if (pieces > 0) {
            BspBank.credit(h.getLevel().getServer(), player.getUUID(), pieces, "test", "test", ok -> {
            });
        }
        return player;
    }

    private static Position spot(GameTestHelper h, double x, double z) {
        return new Position(TeleportConfig.serverName(), h.getLevel().dimension().location().toString(), x, 90, z, 0f, 0f);
    }

    private static boolean near(ServerPlayer player, Position p) {
        return Math.abs(player.getX() - p.x()) < 1 && Math.abs(player.getZ() - p.z()) < 1;
    }

    /** Runs {@code body} with the warm-up off and the spawn warp and /setspawn point put back afterwards. */
    private static void withSpawnSetUp(GameTestHelper h, Runnable body) {
        MinecraftServer server = h.getLevel().getServer();
        TeleportData data = TeleportData.get(server);
        Warp warpBefore = data.warp(NetworkSpawn.warpName());
        Position ownBefore = data.spawn();
        int warmupBefore = TeleportConfig.WARMUP_SECONDS.get();
        try {
            TeleportConfig.WARMUP_SECONDS.set(0);
            body.run();
        } finally {
            TeleportConfig.WARMUP_SECONDS.set(warmupBefore);
            data.setSpawn(ownBefore);
            if (warpBefore != null) {
                data.setWarp(warpBefore);
            } else {
                data.deleteWarp(NetworkSpawn.warpName());
            }
        }
    }

    @GameTest(template = EMPTY)
    public static void spawn_pays_the_spawn_warps_price_and_the_band_shows_it(GameTestHelper h) {
        withSpawnSetUp(h, () -> {
            TeleportData data = TeleportData.get(h.getLevel().getServer());
            Position at = spot(h, 40.5, 40.5);
            data.setSpawn(null);
            data.setWarp(new Warp(NetworkSpawn.warpName(), at, 500, false));

            ServerPlayer rich = withPieces(h, 1000);
            long bank = BspCore.pieces(rich);
            BandData band = BandService.build(rich);
            h.assertTrue(band.hasSpawn() && band.spawnCost() == 500 && band.spawnPieces() && band.spawnAfford(),
                    "the band's Spawn shows 500 pieces the player can pay: " + band.spawnCost() + " " + band.spawnPieces() + " " + band.spawnAfford());
            h.assertTrue(TeleportActions.spawn(rich), "/spawn goes");
            h.assertTrue(near(rich, at), "to the spawn warp: " + rich.position());
            h.assertTrue(BspCore.pieces(rich) == bank - 500, "and takes its 500 pieces: " + BspCore.pieces(rich));

            ServerPlayer poor = withPieces(h, 0);
            h.assertFalse(BandService.build(poor).spawnAfford(), "the band shows a player with no pieces that they cannot pay");
            h.assertFalse(TeleportActions.spawn(poor), "and /spawn refuses them");
            h.assertFalse(near(poor, at), "they did not move");
        });
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_servers_own_spawn_point_stays_free(GameTestHelper h) {
        withSpawnSetUp(h, () -> {
            TeleportData data = TeleportData.get(h.getLevel().getServer());
            Position own = spot(h, -35.5, 20.5);
            data.deleteWarp(NetworkSpawn.warpName());
            data.setSpawn(own);

            ServerPlayer player = withPieces(h, 0);
            BandData band = BandService.build(player);
            h.assertTrue(band.hasSpawn() && band.spawnCost() == 0 && band.spawnAfford(), "Spawn is free with no spawn warp");
            h.assertTrue(TeleportActions.spawn(player), "/spawn goes with nothing in the bank");
            h.assertTrue(near(player, own), "to this server's own spawn point: " + player.position());
        });
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void store_credit_pays_a_warps_own_cost(GameTestHelper h) {
        withSpawnSetUp(h, () -> {
            TeleportData data = TeleportData.get(h.getLevel().getServer());
            Position at = spot(h, 55.5, -30.5);
            String name = "tp_credit_warp_test";
            data.setWarp(new Warp(name, at, 300, false));
            try {
                ServerPlayer player = withPieces(h, 0);
                BspCore.testTeleportTicket(player, 500);
                h.assertTrue(BspCore.pieces(player) == 0 && BspCore.teleportPieces(player) == 500, "an empty bank and 500 in credit");
                Warp warp = data.warp(name);
                h.assertTrue(TeleportActions.affords(player, warp), "the band's GO key is not dimmed for a player paying with credit");
                h.assertTrue(TeleportActions.warp(player, name), "the warp is not refused");
                h.assertTrue(near(player, at), "the player moved: " + player.position());
                h.assertTrue(BspCore.teleportPieces(player) == 200, "and 300 came off the credit: " + BspCore.teleportPieces(player));
            } finally {
                data.deleteWarp(name);
            }
        });
        h.succeed();
    }
}
