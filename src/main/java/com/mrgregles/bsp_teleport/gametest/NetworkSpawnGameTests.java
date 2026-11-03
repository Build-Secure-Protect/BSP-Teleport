package com.mrgregles.bsp_teleport.gametest;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.data.Warp;
import com.mrgregles.bsp_teleport.teleport.NetworkSpawn;
import com.mrgregles.bsp_teleport.teleport.TeleportActions;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * D140, D142, D143: which spot a first visit or a bedless respawn gets. The settle after login and the respawn hook
 * need a real login and a real death and are the owner's in-game check.
 */
@GameTestHolder(BSPTeleport.MODID)
@PrefixGameTestTemplate(false)
public class NetworkSpawnGameTests {
    @GameTest(template = "empty")
    public static void the_servers_own_spawn_comes_first_then_a_network_spawn_on_this_server(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        TeleportData data = TeleportData.get(server);
        Position ownBefore = data.spawn();
        Warp warpBefore = data.warp(NetworkSpawn.warpName());
        String here = TeleportConfig.serverName();
        Position network = new Position(here, "minecraft:overworld", 10.5, 64, 10.5, 0f, 0f);
        Position own = new Position(here, "minecraft:overworld", -20.5, 70, 5.5, 90f, 0f);
        try {
            data.setSpawn(null);
            if (warpBefore != null) {
                data.deleteWarp(warpBefore.name());
            }
            if (NetworkSpawn.localSpot(server) != null) {
                h.fail("no spawn and no network spawn: nothing to place a player at");
                return;
            }
            data.setWarp(new Warp(NetworkSpawn.warpName(), network, 0, false));
            if (!network.equals(NetworkSpawn.localSpot(server))) {
                h.fail("a network spawn on this server is used when the server has none of its own");
                return;
            }
            data.setSpawn(own);
            if (!own.equals(NetworkSpawn.localSpot(server))) {
                h.fail("the server's own /setspawn point comes first");
                return;
            }
            data.setSpawn(null);
            data.setWarp(new Warp(NetworkSpawn.warpName(), new Position("BSP-Elsewhere", "minecraft:overworld", 0, 64, 0, 0f, 0f), 0, false));
            if (NetworkSpawn.localSpot(server) != null) {
                h.fail("a network spawn on another server is never used for a first visit or a respawn");
                return;
            }
            h.succeed();
        } finally {
            data.setSpawn(ownBefore);
            data.deleteWarp(NetworkSpawn.warpName());
            if (warpBefore != null) {
                data.setWarp(warpBefore);
            }
        }
    }

    /** B35: /spawn goes to the network spawn, on any server, even with an empty globalWarp; the own point only without one. */
    @GameTest(template = "empty")
    public static void spawn_goes_to_the_network_spawn_before_the_servers_own(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        TeleportData data = TeleportData.get(server);
        String globalBefore = TeleportConfig.GLOBAL_SPAWN_WARP.get();
        Position ownBefore = data.spawn();
        Warp warpBefore = data.warp("spawn");
        Position own = new Position(TeleportConfig.serverName(), "minecraft:overworld", -20.5, 70, 5.5, 90f, 0f);
        Position network = new Position("BSP-Elsewhere", "minecraft:overworld", 0.5, 64, 0.5, 0f, 0f);
        try {
            TeleportConfig.GLOBAL_SPAWN_WARP.set("");
            data.setSpawn(own);
            data.deleteWarp("spawn");
            if (!own.equals(TeleportActions.spawnPosition(server))) {
                h.fail("with no network spawn, /spawn uses this server's own point");
                return;
            }
            data.setWarp(new Warp("spawn", network, 0, false));
            if (!network.equals(TeleportActions.spawnPosition(server))) {
                h.fail("an empty globalWarp still sends /spawn to the warp 'spawn', on another server too");
                return;
            }
            h.succeed();
        } finally {
            TeleportConfig.GLOBAL_SPAWN_WARP.set(globalBefore);
            data.setSpawn(ownBefore);
            data.deleteWarp("spawn");
            if (warpBefore != null) {
                data.setWarp(warpBefore);
            }
        }
    }
}
