package com.mrgregles.bsp_teleport.gametest;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.api.BspTeleportApi;
import com.mrgregles.bsp_teleport.api.Destination;
import com.mrgregles.bsp_teleport.api.WarpInfo;
import com.mrgregles.bsp_teleport.data.ApiSnapshot;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.data.Warp;
import com.mrgregles.bsp_teleport.teleport.NetworkSpawn;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** T89 (SU-TP1, D202 to D207): {@code BspTeleportApi.warps()} and {@code spawn(server)}. */
@GameTestHolder(BSPTeleport.MODID)
@PrefixGameTestTemplate(false)
public class ApiWarpsSpawnsGameTests {
    private static final String PLAIN = "apitest_plain";
    private static final String PORTAL = "apitest_portal";

    @GameTest(template = "empty")
    public static void warps_are_listed_with_their_positions(GameTestHelper h) {
        TeleportData data = TeleportData.get(h.getLevel().getServer());
        String here = TeleportConfig.serverName();
        Position plainAt = new Position(here, "minecraft:overworld", 12.5, 70, -3.5, 90f, 10f);
        Position portalAt = new Position("BSP-Elsewhere", "minecraft:the_nether", -40.5, 64, 8.5, 0f, 0f);
        try {
            data.setWarp(new Warp(PLAIN, plainAt, 5, true, "minecraft:compass"));
            data.setWarp(new Warp(PORTAL, portalAt, 0, false, "", true));
            // D204: read from another thread, as Server Utilities' web panel does.
            AtomicReference<List<WarpInfo>> seen = new AtomicReference<>();
            Thread reader = new Thread(() -> seen.set(BspTeleportApi.get().warps()), "BSP-Teleport API test reader");
            reader.start();
            reader.join(5000);
            List<WarpInfo> warps = seen.get();
            if (warps == null) {
                h.fail("warps() answered nothing from another thread");
                return;
            }
            WarpInfo plain = find(warps, PLAIN);
            WarpInfo portal = find(warps, PORTAL);
            if (plain == null || portal == null) {
                h.fail("both warps are listed, the portal warp too (D203): " + warps);
                return;
            }
            Destination p = plain.position();
            if (!p.server().equals(here) || !p.world().equals("minecraft:overworld") || p.x() != 12.5 || p.y() != 70 || p.z() != -3.5
                    || p.yaw() != 90f || p.pitch() != 10f) {
                h.fail("the warp's position, with this server named: " + p);
                return;
            }
            if (!plain.restricted() || !plain.icon().equals("minecraft:compass") || plain.portal()) {
                h.fail("restricted, icon and portal flag of the plain warp: " + plain);
                return;
            }
            Destination q = portal.position();
            if (!portal.portal() || !q.server().equals("BSP-Elsewhere") || !q.world().equals("minecraft:the_nether") || q.x() != -40.5) {
                h.fail("the portal warp on another server: " + portal);
                return;
            }
            for (int i = 1; i < warps.size(); i++) {
                if (String.CASE_INSENSITIVE_ORDER.compare(warps.get(i - 1).name(), warps.get(i).name()) > 0) {
                    h.fail("warps() is sorted by name: " + warps);
                    return;
                }
            }
            try {
                warps.clear();
                h.fail("the list cannot be changed");
                return;
            } catch (UnsupportedOperationException expected) {
                // read-only, as documented
            }
            data.deleteWarp(PLAIN);
            if (find(BspTeleportApi.get().warps(), PLAIN) != null) {
                h.fail("a deleted warp leaves the list");
                return;
            }
            h.succeed();
        } catch (InterruptedException e) {
            h.fail("interrupted");
        } finally {
            data.deleteWarp(PLAIN);
            data.deleteWarp(PORTAL);
        }
    }

    @GameTest(template = "empty")
    public static void a_spawn_is_found_on_this_server_and_another(GameTestHelper h) {
        TeleportData data = TeleportData.get(h.getLevel().getServer());
        Position before = data.spawn();
        String here = TeleportConfig.serverName();
        Position own = new Position(here, "minecraft:overworld", -20.5, 71, 5.5, 180f, 0f);
        Position other = new Position("BSP-Elsewhere", "minecraft:overworld", 100.5, 80, 200.5, 0f, 0f);
        try {
            data.setSpawn(own);
            ApiSnapshot.replaceSpawns(Map.of("BSP-Elsewhere", other));
            BspTeleportApi api = BspTeleportApi.get();
            for (String name : List.of("", here, here.toUpperCase(Locale.ROOT), here.toLowerCase(Locale.ROOT))) {
                Optional<Destination> d = api.spawn(name);
                if (d.isEmpty() || !d.get().server().equals(here) || d.get().x() != -20.5 || d.get().y() != 71 || d.get().yaw() != 180f) {
                    h.fail("spawn(\"" + name + "\") is this server's /setspawn point (D207): " + d);
                    return;
                }
            }
            Optional<Destination> there = api.spawn("bsp-elsewhere");
            if (there.isEmpty() || !there.get().server().equals("BSP-Elsewhere") || there.get().z() != 200.5) {
                h.fail("another server's spawn from the shared table, matched ignoring case: " + there);
                return;
            }
            h.succeed();
        } finally {
            data.setSpawn(before);
            ApiSnapshot.replaceSpawns(Map.of());
        }
    }

    @GameTest(template = "empty")
    public static void an_unknown_server_or_no_setspawn_gives_empty(GameTestHelper h) {
        TeleportData data = TeleportData.get(h.getLevel().getServer());
        Position before = data.spawn();
        Warp warpBefore = data.warp(NetworkSpawn.warpName());
        String here = TeleportConfig.serverName();
        try {
            BspTeleportApi api = BspTeleportApi.get();
            if (api.spawn("BSP-NoSuchServer").isPresent()) {
                h.fail("an unknown server has no spawn");
                return;
            }
            data.setSpawn(null);
            data.setWarp(new Warp(NetworkSpawn.warpName(), new Position(here, "minecraft:overworld", 1.5, 64, 1.5, 0f, 0f), 0, false));
            if (api.spawn("").isPresent()) {
                h.fail("the network spawn warp is not this server's spawn (D206)");
                return;
            }
            if (find(api.warps(), NetworkSpawn.warpName()) == null) {
                h.fail("the network spawn warp is in warps() under its own name");
                return;
            }
            h.succeed();
        } finally {
            data.setSpawn(before);
            data.deleteWarp(NetworkSpawn.warpName());
            if (warpBefore != null) {
                data.setWarp(warpBefore);
            }
        }
    }

    private static WarpInfo find(List<WarpInfo> warps, String name) {
        for (WarpInfo w : warps) {
            if (w.name().equalsIgnoreCase(name)) {
                return w;
            }
        }
        return null;
    }
}
