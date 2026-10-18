package com.mrgregles.bsp_teleport.client;

import com.mrgregles.bsp_teleport.access.AccessGrant;
import com.mrgregles.bsp_teleport.access.AccessSync;
import com.mrgregles.bsp_teleport.access.TokenAssignment;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The client's copy of the player's access (D79): what the HUD, the tooltips and the band's ACCESS list read. */
public final class ClientAccess {
    /** A grant with its expiry on the client's clock. */
    public record Entry(AccessGrant.Kind kind, String target, AccessGrant.Tier tier, long expiresAt) {
        public long remainingMs() {
            return tier == AccessGrant.Tier.RENT ? Math.max(0, expiresAt - System.currentTimeMillis()) : Long.MAX_VALUE;
        }
    }

    private static boolean enabled;
    private static List<Entry> grants = List.of();
    private static final Map<Integer, TokenAssignment> TOKENS = new HashMap<>();
    private static List<String> restricted = List.of();
    private static List<Count> counts = List.of();

    /** A restricted server's counts (D85). */
    public record Count(String server, int home, int full, int rent) {
    }

    private ClientAccess() {
    }

    public static void accept(AccessSync sync) {
        enabled = sync.enabled();
        long now = System.currentTimeMillis();
        List<Entry> list = new ArrayList<>();
        for (AccessSync.Grant g : sync.grants()) {
            list.add(new Entry(g.kind(), g.target(), g.tier(), g.tier() == AccessGrant.Tier.RENT ? now + g.remainingMs() : 0));
        }
        grants = list;
        TOKENS.clear();
        sync.tokens().forEach(t -> TOKENS.put(t.token(), t));
        restricted = new ArrayList<>(sync.restricted());
        List<Count> list2 = new ArrayList<>();
        for (AccessSync.Count c : sync.counts()) {
            list2.add(new Count(c.server(), c.home(), c.full(), c.rent()));
        }
        counts = list2;
    }

    public static List<Count> counts() {
        return counts;
    }

    public static void clear() {
        enabled = false;
        grants = List.of();
        TOKENS.clear();
        restricted = List.of();
        counts = List.of();
    }

    public static boolean enabled() {
        return enabled;
    }

    public static List<Entry> grants() {
        return grants;
    }

    public static List<String> restricted() {
        return restricted;
    }

    @Nullable
    public static TokenAssignment token(int index) {
        return TOKENS.get(index);
    }

    /** The token number that stands for a server, or 0. */
    public static int tokenFor(AccessGrant.Kind kind, String target) {
        for (TokenAssignment t : TOKENS.values()) {
            if (t.kind() == kind && t.target().equalsIgnoreCase(target)) {
                return t.token();
            }
        }
        return 0;
    }

    /** The server grants in HUD order: main, then full, then rented (D79); live ones only. */
    public static List<Entry> hudEntries() {
        List<Entry> out = new ArrayList<>();
        for (AccessGrant.Tier tier : new AccessGrant.Tier[]{AccessGrant.Tier.MAIN, AccessGrant.Tier.FULL, AccessGrant.Tier.RENT}) {
            for (Entry e : grants) {
                if (e.kind() == AccessGrant.Kind.SERVER && e.tier() == tier && e.remainingMs() > 0) {
                    out.add(e);
                }
            }
        }
        return out;
    }
}
