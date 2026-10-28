package com.mrgregles.bsp_teleport.storage;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.api.Destination;
import com.mrgregles.bsp_teleport.data.Home;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.access.AccessGrant;
import com.mrgregles.bsp_teleport.access.AccessService;
import com.mrgregles.bsp_teleport.access.TokenAssignment;
import com.mrgregles.bsp_teleport.data.PortalRow;
import com.mrgregles.bsp_teleport.data.Warp;
import com.mrgregles.bsp_teleport.portal.Portals;
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

    /** The status line with the live warp count, for the panel and {@code /bsptp status}. */
    public static String status(MinecraftServer server) {
        return store == null ? status : status + ", " + TeleportData.get(server).warps().size() + " warps";
    }

    /** Every five minutes: re-read the warps, in case a change notice was missed while Redis was away. */
    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase == net.minecraftforge.event.TickEvent.Phase.END && store != null && event.getServer().getTickCount() % 6000 == 0) {
            reloadWarps(event.getServer());
            reloadPortals(event.getServer());
        }
    }

    // Lifecycle

    public static void onServerStarted(ServerStartedEvent event) {
        if (!TeleportConfig.mysql()) {
            // D146: the world save's own rows are checked once at start (with the shared table, after its first read).
            com.mrgregles.bsp_teleport.portal.Portals.checkOwnRows(event.getServer(), com.mrgregles.bsp_teleport.portal.Portals.rows());
            status = "local (world save)";
            if (TeleportConfig.singlePlayer()) {
                status = "this world's save (single player)";
                BSPTeleport.LOGGER.info("Single player: network features off (no MySQL, Redis or proxy, whatever the config says)");
            }
            return;
        }
        MySqlStore s = new MySqlStore(NetworkConfig.DB_HOST.get(), NetworkConfig.DB_PORT.get(), NetworkConfig.DB_DATABASE.get(),
                NetworkConfig.DB_USER.get(), NetworkConfig.DB_PASSWORD.get(), NetworkConfig.DB_TABLE_PREFIX.get(), NetworkConfig.DB_SSL.get());
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
            return new Loaded(s.loadWarps(), s.loadSpawn(TeleportConfig.serverName()), s.loadPortals());
        }, loaded -> {
            TeleportData data = TeleportData.get(server);
            data.replaceWarps(loaded.warps());
            if (loaded.spawn() != null) {
                data.setSpawn(loaded.spawn());
            }
            Portals.replaceRows(loaded.portals());
            status = "mysql: connected to " + s.describe();
            reloadAccessSettings();
            reloadAccessCounts();
            BSPTeleport.LOGGER.info("Storage ready: {}, {} warps", status, loaded.warps().size());
        }, e -> status = "mysql: FAILED, " + e.getMessage());
    }

    private record Loaded(List<Warp> warps, @Nullable Position spawn, List<PortalRow> portals) {
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
            // D141: no record before this login's touch means the player is new to the whole network.
            // Audit: one read; the touch changes only the name and last server, which the load does not use.
            MySqlStore.UserRow before = s.loadUser(uuid);
            s.touchUser(uuid, name, TeleportConfig.serverName());
            return new UserLoad(before, s.loadHomes(uuid), before == null);
        }, load -> {
            if (load.newToNetwork()) {
                com.mrgregles.bsp_teleport.teleport.NetworkSpawn.markNewToNetwork(uuid);
            }
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

    private record UserLoad(@Nullable MySqlStore.UserRow user, List<Home> homes, boolean newToNetwork) {
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

    // The change notice goes out only once the row is written: a server that re-reads on the notice must see the new row.

    public static void homeSaved(UUID owner, Home home) {
        submit(s -> s.saveHome(owner, home), () -> Messenger.homesChanged(owner));
    }

    public static void homeDeleted(UUID owner, String name) {
        submit(s -> s.deleteHome(owner, name), () -> Messenger.homesChanged(owner));
    }

    public static void reloadPortals(MinecraftServer server) {
        MySqlStore s = store;
        if (s == null) {
            return;
        }
        run(s::loadPortals, Portals::replaceRows, null);
    }

    // Access (D87): grants per player on login and on notice; tokens and restricted servers for everyone.

    public static void loadAccess(UUID uuid, Consumer<List<AccessGrant>> then) {
        MySqlStore s = store;
        if (s == null) {
            return;
        }
        run(() -> s.loadAccess(uuid), then, null);
    }

    public static void accessSaved(AccessGrant grant) {
        submit(s -> s.saveAccess(grant), () -> Messenger.accessChanged(grant.player().toString()));
    }

    public static void accessDeleted(UUID uuid, AccessGrant.Kind kind, String target) {
        submit(s -> s.deleteAccess(uuid, kind, target), () -> Messenger.accessChanged(uuid.toString()));
    }

    public static void tokenSaved(TokenAssignment token) {
        submit(s -> s.saveToken(token), () -> Messenger.accessChanged(""));
    }

    public static void tokenDeleted(int token) {
        submit(s -> s.deleteToken(token), () -> Messenger.accessChanged(""));
    }

    public static void restrictedSet(String server, boolean on) {
        submit(s -> s.setRestricted(server, on), () -> Messenger.accessChanged(""));
    }

    /** Re-reads the token assignments and the restricted servers into the service. */
    public static void reloadAccessSettings() {
        MySqlStore s = store;
        if (s == null) {
            return;
        }
        run(() -> new AccessSettings(s.loadTokens(), s.loadRestrictedServers()), loaded -> AccessService.replaceSettings(loaded.tokens(), loaded.servers()), null);
    }

    public static void reloadAccessCounts() {
        MySqlStore s = store;
        if (s == null) {
            return;
        }
        run(() -> s.loadAccessCounts(System.currentTimeMillis()), AccessService::replaceCounts, null);
    }

    /** Another server's spawn, for a bounce to the lobby (D69); null when it has none. */
    public static void spawnOf(String server, Consumer<Position> then) {
        MySqlStore s = store;
        if (s == null) {
            then.accept(null);
            return;
        }
        run(() -> s.loadSpawn(server), then, e -> then.accept(null));
    }

    private record AccessSettings(List<TokenAssignment> tokens, List<String> servers) {
    }

    public static void portalSaved(PortalRow row) {
        submit(s -> s.savePortal(row), Messenger::portalsChanged);
    }

    public static void portalDeleted(String id) {
        submit(s -> s.deletePortal(id), Messenger::portalsChanged);
    }

    public static void warpSaved(Warp warp) {
        submit(s -> s.saveWarp(warp), Messenger::warpsChanged);
    }

    public static void warpDeleted(String name) {
        submit(s -> s.deleteWarp(name), Messenger::warpsChanged);
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
    /** Reads the player's pending landing, if any, leaving the row in place; {@code then} runs on the server thread. */
    public static void peekPending(UUID uuid, Consumer<Destination> then) {
        MySqlStore s = store;
        if (s == null) {
            return;
        }
        long maxAge = TeleportConfig.getOr(TeleportConfig.LANDING_SECONDS, 120) * 1000L;
        run(() -> s.peekPending(uuid, maxAge), then, null);
    }

    /** Removes the player's pending landing once it has been applied. */
    public static void deletePending(UUID uuid) {
        submit(s -> s.deletePending(uuid));
    }

    // Worker plumbing

    private interface Work {
        void run(MySqlStore store) throws Exception;
    }

    private static void submit(Work work) {
        submit(work, null);
    }

    /** Runs a write on the worker; {@code afterWrite} runs on the server thread once it succeeded. */
    private static void submit(Work work, @Nullable Runnable afterWrite) {
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
                return;
            }
            if (afterWrite != null) {
                onServer(afterWrite);
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
