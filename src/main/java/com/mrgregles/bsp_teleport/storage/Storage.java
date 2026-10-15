package com.mrgregles.bsp_teleport.storage;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.api.Destination;
import com.mrgregles.bsp_teleport.data.Home;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.data.Warp;
import com.mrgregles.bsp_teleport.network.Messenger;
import com.mrgregles.bsp_teleport.teleport.Teleports;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The shared store, when {@code storage.mode = mysql}.
 *
 * <p>{@link TeleportData} stays the cache every command reads: in network mode it is filled from the database (a
 * player's homes on join, every warp and this server's spawn at start) and every change is written through from here
 * on one worker thread, with the answer delivered back on the server thread. In local mode every method here is a
 * no-op and {@link TeleportData} is the only store.
 */
public final class Storage {
    @Nullable
    private static MySqlStore store;
    @Nullable
    private static ExecutorService worker;
    private static volatile String status = "local";

    private Storage() {
    }

    public static boolean enabled() {
        return store != null;
    }

    public static String status() {
        return status;
    }

    // Lifecycle

    public static void onServerStarted(ServerStartedEvent event) {
        if (!TeleportConfig.mysql()) {
            status = "local (world save)";
            return;
        }
        MySqlStore s = new MySqlStore(TeleportConfig.STORAGE_HOST.get(), TeleportConfig.STORAGE_PORT.get(), TeleportConfig.STORAGE_DATABASE.get(),
                TeleportConfig.STORAGE_USER.get(), TeleportConfig.STORAGE_PASSWORD.get(), TeleportConfig.STORAGE_TABLE_PREFIX.get(), TeleportConfig.STORAGE_SSL.get());
        store = s;
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "BSP-Teleport storage");
            t.setDaemon(true);
            return t;
        });
        status = "mysql: connecting to " + s.describe();
        MinecraftServer server = event.getServer();
        run(() -> {
            s.init();
            s.deleteOldPending(TeleportConfig.getOr(TeleportConfig.LANDING_SECONDS, 120) * 1000L);
            return new Loaded(s.loadWarps(), s.loadSpawn(TeleportConfig.serverName()));
        }, loaded -> {
            TeleportData data = TeleportData.get(server);
            data.replaceWarps(loaded.warps());
            if (loaded.spawn() != null) {
                data.setSpawn(loaded.spawn());
            }
            status = "mysql: connected to " + s.describe() + ", " + loaded.warps().size() + " warps";
            BSPTeleport.LOGGER.info("Storage ready: {}", status);
        }, e -> status = "mysql: FAILED, " + e.getMessage());
    }

    private record Loaded(List<Warp> warps, @Nullable Position spawn) {
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        ExecutorService w = worker;
        MySqlStore s = store;
        store = null;
        worker = null;
        if (w != null) {
            w.execute(() -> {
                if (s != null) {
                    s.close();
                }
            });
            w.shutdown();
            try {
                w.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // Players

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || store == null) {
            return;
        }
        MySqlStore s = store;
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().getName();
        run(() -> {
            s.touchUser(uuid, name, TeleportConfig.serverName());
            return new UserLoad(s.loadUser(uuid), s.loadHomes(uuid));
        }, load -> {
            TeleportData data = TeleportData.get(player.server);
            data.replaceHomes(uuid, load.homes());
            if (load.user() != null) {
                Teleports.restore(uuid, load.user().cooldownUntil(), load.user().back());
                data.setIgnoring(uuid, load.user().ignoring());
            }
        }, null);
    }

    /** A player known to the network by name: uuid, offline position, homes. {@code then} gets null when unknown. */
    public static void lookup(String name, Consumer<Lookup> then) {
        MySqlStore s = store;
        if (s == null) {
            return;
        }
        run(() -> {
            MySqlStore.UserRow user = s.findUser(name);
            return user == null ? null : new Lookup(user.uuid(), user.name(), user.offline(), s.loadHomes(user.uuid()));
        }, then, e -> then.accept(null));
    }

    public record Lookup(UUID uuid, String name, @Nullable Position offline, List<Home> homes) {
    }

    /** Every public home on the network as {@code (ownerName, home)}; {@code then} runs on the server thread. */
    public static void publicHomes(Consumer<List<PublicHome>> then) {
        MySqlStore s = store;
        if (s == null) {
            return;
        }
        run(() -> {
            List<PublicHome> out = new ArrayList<>();
            for (MySqlStore.PublicHome p : s.loadPublicHomes()) {
                out.add(new PublicHome(p.owner(), p.ownerName(), p.home()));
            }
            return out;
        }, then, e -> then.accept(List.of()));
    }

    public record PublicHome(UUID owner, String ownerName, Home home) {
    }

    private record UserLoad(@Nullable MySqlStore.UserRow user, List<Home> homes) {
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (store != null && event.getEntity() instanceof ServerPlayer player) {
            TeleportData.get(player.server).forgetHomes(player.getUUID());
        }
    }

    public static void offlineSet(UUID uuid, Position where) {
        submit(s -> s.saveOffline(uuid, where));
    }

    public static void ignoringSet(UUID uuid, boolean ignoring) {
        submit(s -> s.saveIgnoring(uuid, ignoring));
    }

    /** Re-reads a player's homes (after another server changed them). */
    public static void reloadHomes(ServerPlayer player) {
        MySqlStore s = store;
        if (s == null) {
            return;
        }
        UUID uuid = player.getUUID();
        run(() -> s.loadHomes(uuid), homes -> TeleportData.get(player.server).replaceHomes(uuid, homes), null);
    }

    public static void reloadWarps(MinecraftServer server) {
        MySqlStore s = store;
        if (s == null) {
            return;
        }
        run(s::loadWarps, warps -> TeleportData.get(server).replaceWarps(warps), null);
    }

    // Write-through

    public static void homeSaved(UUID owner, Home home) {
        submit(s -> s.saveHome(owner, home));
        Messenger.homesChanged(owner);
    }

    public static void homeDeleted(UUID owner, String name) {
        submit(s -> s.deleteHome(owner, name));
        Messenger.homesChanged(owner);
    }

    public static void warpSaved(Warp warp) {
        submit(s -> s.saveWarp(warp));
        Messenger.warpsChanged();
    }

    public static void warpDeleted(String name) {
        submit(s -> s.deleteWarp(name));
        Messenger.warpsChanged();
    }

    public static void wipeHomes() {
        submit(MySqlStore::deleteAllHomes);
    }

    public static void spawnSaved(Position spawn) {
        submit(s -> s.saveSpawn(TeleportConfig.serverName(), spawn));
    }

    public static void cooldownSet(UUID uuid, long until) {
        submit(s -> s.saveCooldown(uuid, until));
    }

    public static void backSet(UUID uuid, Position back) {
        submit(s -> s.saveBack(uuid, back));
    }

    // Cross-server landings

    /** Writes the landing row, then runs {@code then} on the server thread (also when there is no store). */
    public static void savePending(UUID uuid, Destination destination, Runnable then) {
        MySqlStore s = store;
        if (s == null) {
            then.run();
            return;
        }
        run(() -> {
            s.savePending(uuid, destination);
            return Boolean.TRUE;
        }, ok -> then.run(), e -> then.run());
    }

    /** Looks up and clears the player's landing row; {@code then} gets null when there is none. */
    public static void takePending(UUID uuid, Consumer<Destination> then) {
        MySqlStore s = store;
        if (s == null) {
            return;
        }
        long maxAge = TeleportConfig.getOr(TeleportConfig.LANDING_SECONDS, 120) * 1000L;
        run(() -> s.takePending(uuid, maxAge), then, null);
    }

    // Worker plumbing

    private interface Work {
        void run(MySqlStore store) throws Exception;
    }

    private static void submit(Work work) {
        MySqlStore s = store;
        ExecutorService w = worker;
        if (s == null || w == null) {
            return;
        }
        w.execute(() -> {
            try {
                work.run(s);
            } catch (Exception e) {
                BSPTeleport.LOGGER.error("Storage write failed: {}", e.toString());
                status = "mysql: last write failed, " + e.getMessage();
            }
        });
    }

    private static <T> void run(Callable<T> work, Consumer<T> onServerThread, @Nullable Consumer<Exception> onError) {
        ExecutorService w = worker;
        if (w == null) {
            return;
        }
        w.execute(() -> {
            T result;
            try {
                result = work.call();
            } catch (Exception e) {
                BSPTeleport.LOGGER.error("Storage read failed: {}", e.toString());
                if (onError != null) {
                    onServer(() -> onError.accept(e));
                }
                return;
            }
            onServer(() -> onServerThread.accept(result));
        });
    }

    private static void onServer(Runnable r) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            server.execute(r);
        }
    }
}
