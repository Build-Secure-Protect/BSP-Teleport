package com.mrgregles.bsp_teleport.access;

import com.mojang.authlib.GameProfile;
import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.compat.BspCore;
import com.mrgregles.bsp_teleport.compat.LuckPerms;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.perm.Permissions;
import com.mrgregles.bsp_teleport.storage.Storage;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraftforge.event.entity.player.PlayerNegotiationEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * B39: a player without access to this server is refused while still logging in, instead of being let in and moved to
 * the lobby by a proxy switch. A refused first join is a failed connect to the proxy: BSP-Velocity Additions sends the
 * player to spawn (and remembers spawn, not this server), with no Forge reset on the client. The switch after a login
 * was what kicked them with "Can't serialize unregistered packet", every time, because the proxy kept sending them back.
 *
 * <p>Forge holds the login until the work queued on {@link PlayerNegotiationEvent} is done; LuckPerms loads the player
 * in the same way. The grants and the admin node are read off the server thread, then the answer is made on it. When
 * the answer cannot be certain (no LuckPerms Forge edition, so admins cannot be told apart yet; the database or
 * LuckPerms not answering within {@link #WAIT_MS}), the player is let in and {@code AccessService}'s check after the
 * login still sends them off.
 */
public final class LoginCheck {
    /** How long a login waits for the database or LuckPerms before the player is let in to be checked later. */
    static final long WAIT_MS = 4_000;

    private static ExecutorService pool;

    private LoginCheck() {
    }

    public static void onNegotiation(PlayerNegotiationEvent event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        GameProfile profile = event.getProfile();
        if (server == null || profile == null || profile.getId() == null || !TeleportConfig.getOr(TeleportConfig.ACCESS_REFUSE_AT_LOGIN, false)) {
            return;
        }
        String here = TeleportConfig.serverName();
        if (!AccessService.enabled() || !AccessService.isRestricted(server, here) || !LuckPerms.isPresent()) {
            return;
        }
        UUID uuid = profile.getId();
        Future<List<AccessGrant>> grants = Storage.enabled() ? Storage.loadAccessNow(uuid)
                : CompletableFuture.completedFuture(TeleportData.get(server).accessOf(uuid));
        if (grants == null) {
            return;
        }
        Connection connection = event.getConnection();
        event.enqueueWork(CompletableFuture.runAsync(() -> check(server, connection, profile, grants), pool()));
    }

    /** On the login check's own thread: waits for the reads, then answers on the server thread. */
    private static void check(MinecraftServer server, Connection connection, GameProfile profile, Future<List<AccessGrant>> grants) {
        String name = profile.getName();
        try {
            List<AccessGrant> list = grants.get(WAIT_MS, TimeUnit.MILLISECONDS);
            java.util.Optional<Boolean> admin = LuckPerms.checkLoggingIn(profile.getId(), Permissions.ADMIN, WAIT_MS);
            if (admin.isEmpty()) {
                BSPTeleport.LOGGER.info("{}: LuckPerms had no answer at login; access is checked once they are in", name);
                return;
            }
            if (admin.get()) {
                return;
            }
            server.submit(() -> {
                Component refusal = refusal(server, profile, list);
                if (refusal != null && connection.isConnected()) {
                    BSPTeleport.LOGGER.info("{} has no access to {}: refused at login, so the proxy sends them on", name, TeleportConfig.serverName());
                    if (connection.getPacketListener() instanceof ServerLoginPacketListenerImpl login) {
                        login.disconnect(refusal);
                    } else {
                        connection.disconnect(refusal);
                    }
                }
            }).get(WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
            BSPTeleport.LOGGER.info("{}: the access check at login did not finish ({}); access is checked once they are in", name, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The disconnect line for a player logging in here without access, or null when they may come in: the server is
     * open, a live grant covers it, or BSP-Core counts them an admin (an operator, or one of its named admins). The
     * {@code bsp_teleport.admin} node is checked before this, through LuckPerms.
     */
    @Nullable
    public static Component refusal(MinecraftServer server, GameProfile profile, List<AccessGrant> grants) {
        String here = TeleportConfig.serverName();
        if (!AccessService.enabled() || BspCore.isAdminProfile(server, profile)
                || AccessRules.mayEnter(grants, AccessService.restricted(server), here, System.currentTimeMillis())) {
            return null;
        }
        return Component.translatableWithFallback("message.bsp_teleport.access.refused_login", "You have no access to " + here + ".", here);
    }

    private static synchronized ExecutorService pool() {
        if (pool == null) {
            pool = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "BSP-Teleport login check");
                t.setDaemon(true);
                return t;
            });
        }
        return pool;
    }

    /** The server stopped: the threads go with it. */
    public static synchronized void reset() {
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }
}
