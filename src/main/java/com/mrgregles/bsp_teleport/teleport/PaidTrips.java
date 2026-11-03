package com.mrgregles.bsp_teleport.teleport;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.access.AccessChecks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * B34: a paid move to another server, watched until the player has left. The switch happens after this server's part is
 * done (the switch gap, the one-second hold, then the proxy), so a failure shows up only later: still here long after the
 * proxy was asked (the other server would not take them), never asked at all (they died or the move was dropped), or
 * logged out before it was asked (a crash during the hold). Each of those gives back what was paid. Leaving after the
 * proxy was asked counts as the move; a server that takes the player and then sends them back is not seen from here.
 */
public final class PaidTrips {
    /** Still here this long after the proxy was asked: the switch did not go through. Velocity gives up well before this. */
    static final long ASKED_TIMEOUT_MS = 45_000;
    /** Never asked this long after paying: the departure was dropped (the switch gap is a few seconds, the hold one). */
    static final long UNASKED_TIMEOUT_MS = 60_000;
    /** Asked this long ago and starting another paid move from here: the first switch did not go through (audit). */
    static final long RETRY_AFTER_MS = 5_000;

    /** @param undo what else the failed move takes back (the warm-up's cooldown), or null */
    private record Trip(ServerPlayer player, AccessChecks.Paid paid, String server, long since, boolean asked, @Nullable Runnable undo) {
    }

    private static final Map<UUID, Trip> TRIPS = new HashMap<>();
    private static int ticks;

    private PaidTrips() {
    }

    /**
     * Watches a paid move to {@code server} that has started. A second one replaces the first. Audit: when the proxy was
     * asked for the first a while ago and the player is still here paying for another (they retried after the switch
     * failed), the first is given back now instead of counting as made; a first one still starting counts as made.
     */
    public static void watch(ServerPlayer player, @Nullable AccessChecks.Paid paid, String server, @Nullable Runnable undo) {
        if (paid == null || paid.nothing()) {
            return;
        }
        long now = System.currentTimeMillis();
        Trip old = TRIPS.put(player.getUUID(), new Trip(player, paid, server, now, false, undo));
        if (old != null && old.asked() && now - old.since() >= RETRY_AFTER_MS) {
            BSPTeleport.LOGGER.info("{}'s paid move to '{}' did not happen (they paid for another from here)", player.getGameProfile().getName(), old.server());
            // Only the payment: the cooldown now belongs to the new move, which wrote it just before this.
            AccessChecks.refund(player.server, old.paid(), player);
        }
    }

    /** The proxy has been asked to move the player now. */
    public static void asked(ServerPlayer player) {
        Trip t = TRIPS.get(player.getUUID());
        if (t != null) {
            TRIPS.put(player.getUUID(), new Trip(t.player(), t.paid(), t.server(), System.currentTimeMillis(), true, t.undo()));
        }
    }

    /** Leaving after the proxy was asked is the move; leaving before is not, and what they paid goes back. */
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        Trip t = TRIPS.remove(event.getEntity().getUUID());
        if (t != null && !t.asked()) {
            BSPTeleport.LOGGER.info("{} left before their paid move to '{}' started", t.player().getGameProfile().getName(), t.server());
            fail(t, null);
        }
    }

    /** Items owed from a move that failed while the player was gone. */
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AccessChecks.giveOwed(player);
        }
    }

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || TRIPS.isEmpty() || ++ticks % 20 != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Trip>> it = TRIPS.entrySet().iterator();
        while (it.hasNext()) {
            Trip t = it.next().getValue();
            if (now - t.since() < (t.asked() ? ASKED_TIMEOUT_MS : UNASKED_TIMEOUT_MS)) {
                continue;
            }
            it.remove();
            boolean here = !t.player().hasDisconnected() && !t.player().isRemoved();
            BSPTeleport.LOGGER.info("{}'s paid move to '{}' did not happen ({})", t.player().getGameProfile().getName(), t.server(),
                    t.asked() ? "still here after the proxy was asked" : "the switch never started");
            fail(t, here ? t.player() : null);
        }
    }

    private static void fail(Trip t, @Nullable ServerPlayer online) {
        if (t.undo() != null) {
            t.undo().run();
        }
        AccessChecks.refund(t.player().server, t.paid(), online);
    }

    /** The server is stopping: everyone is leaving, so nothing more is decided here. */
    public static void clear() {
        TRIPS.clear();
    }

    /** For tests: whether a paid move of the player's is being watched. */
    public static boolean watching(ServerPlayer player) {
        return TRIPS.containsKey(player.getUUID());
    }

    /** For tests: the player's watched move as if it had been asked {@code ms} ago, with no timeout check. */
    public static void agedForTest(ServerPlayer player, long ms) {
        Trip t = TRIPS.get(player.getUUID());
        if (t != null) {
            TRIPS.put(player.getUUID(), new Trip(t.player(), t.paid(), t.server(), t.since() - ms, t.asked(), t.undo()));
        }
    }

    /** For tests: runs the timeout check as if {@code ms} had passed. */
    public static void expireForTest(ServerPlayer player, long ms) {
        Trip t = TRIPS.get(player.getUUID());
        if (t != null) {
            TRIPS.put(player.getUUID(), new Trip(t.player(), t.paid(), t.server(), t.since() - ms, t.asked(), t.undo()));
        }
        ticks = 19;
        onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.END, () -> true, t == null ? null : t.player().server));
    }
}
