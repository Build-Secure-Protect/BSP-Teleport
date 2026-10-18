package com.mrgregles.bsp_teleport.access;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** The pure rules of D68 to D78, on lists of grants, so they have a unit test. */
public final class AccessRules {
    private AccessRules() {
    }

    /** The player's main server, or null. */
    @Nullable
    public static String mainServer(Collection<AccessGrant> grants) {
        for (AccessGrant g : grants) {
            if (g.kind() == AccessGrant.Kind.SERVER && g.tier() == AccessGrant.Tier.MAIN) {
                return g.target();
            }
        }
        return null;
    }

    /** Whether the player may enter the server (D68): not restricted, their main server, or a live full or rented grant. */
    public static boolean mayEnter(Collection<AccessGrant> grants, Set<String> restricted, String server, long now) {
        if (server.isEmpty() || !contains(restricted, server)) {
            return true;
        }
        return has(grants, AccessGrant.Kind.SERVER, server, now);
    }

    /** Whether some live grant covers the thing (any tier). */
    public static boolean has(Collection<AccessGrant> grants, AccessGrant.Kind kind, String target, long now) {
        for (AccessGrant g : grants) {
            if (g.sameThing(kind, target) && g.active(now)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public static AccessGrant find(Collection<AccessGrant> grants, AccessGrant.Kind kind, String target) {
        for (AccessGrant g : grants) {
            if (g.sameThing(kind, target)) {
                return g;
            }
        }
        return null;
    }

    /** Why a grant cannot be applied, or null when it can (D75). */
    @Nullable
    public static String refuse(Collection<AccessGrant> grants, AccessGrant.Kind kind, String target, AccessGrant.Tier tier, long now) {
        AccessGrant existing = find(grants, kind, target);
        if (tier == AccessGrant.Tier.MAIN) {
            return mainServer(grants) != null ? "access.main_already" : null;
        }
        if (existing == null || !existing.active(now)) {
            return null;
        }
        if (existing.tier() == AccessGrant.Tier.MAIN || existing.tier() == AccessGrant.Tier.FULL) {
            return "access.already_full";
        }
        return tier == AccessGrant.Tier.RENT ? null : null;
    }

    /**
     * The grant list after applying a tier (D75): rent adds to rent time left, full replaces rent, main is set once.
     * Call {@link #refuse} first; this assumes the grant is allowed.
     */
    public static List<AccessGrant> apply(Collection<AccessGrant> grants, AccessGrant grant, long now) {
        List<AccessGrant> out = new ArrayList<>();
        AccessGrant merged = grant;
        for (AccessGrant g : grants) {
            if (g.sameThing(grant.kind(), grant.target())) {
                if (g.tier() == AccessGrant.Tier.RENT && grant.tier() == AccessGrant.Tier.RENT && g.active(now)) {
                    merged = grant.withExpiry(g.expiresMs() + (grant.expiresMs() - now));
                }
                continue;
            }
            out.add(g);
        }
        out.add(merged);
        return out;
    }

    /** Milliseconds a rent of so many hours lasts. */
    public static long rentMs(int hours) {
        return Math.max(1, hours) * 3_600_000L;
    }

    /** The warning stage a remaining time falls in: 3 under a minute, 2 under ten, 1 under an hour, 0 otherwise (D78). */
    public static int warningStage(long remainingMs) {
        if (remainingMs <= 60_000) {
            return 3;
        }
        if (remainingMs <= 600_000) {
            return 2;
        }
        if (remainingMs <= 3_600_000) {
            return 1;
        }
        return 0;
    }

    private static boolean contains(Set<String> set, String value) {
        for (String s : set) {
            if (s.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
