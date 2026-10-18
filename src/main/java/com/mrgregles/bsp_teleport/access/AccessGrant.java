package com.mrgregles.bsp_teleport.access;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * One thing a player may enter (D87): a server, a region (target {@code server:regionId}, D84), a portal or a warp.
 *
 * @param tier      MAIN (the free, permanent home server, D71), FULL (bought), RENT (bought for a time)
 * @param expiresMs when a RENT ends in wall-clock milliseconds; 0 for the other tiers
 */
public record AccessGrant(UUID player, Kind kind, String target, Tier tier, long expiresMs, long grantedMs) {
    public enum Kind { SERVER, REGION, PORTAL, WARP }

    public enum Tier { MAIN, FULL, RENT }

    public boolean active(long now) {
        return tier != Tier.RENT || expiresMs > now;
    }

    public long remainingMs(long now) {
        return tier == Tier.RENT ? Math.max(0, expiresMs - now) : Long.MAX_VALUE;
    }

    public boolean sameThing(Kind kind, String target) {
        return this.kind == kind && this.target.equalsIgnoreCase(target);
    }

    public AccessGrant withExpiry(long expiresMs) {
        return new AccessGrant(player, kind, target, tier, expiresMs, grantedMs);
    }

    /** "6d 5h", "23h 59m" or "9m" (D77). */
    public static String describeLeft(long ms) {
        long minutes = (ms + 59_999) / 60_000;
        long days = minutes / 1440, hours = (minutes % 1440) / 60, mins = minutes % 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + mins + "m";
        }
        return mins + "m";
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Player", player);
        tag.putString("Kind", kind.name());
        tag.putString("Target", target);
        tag.putString("Tier", tier.name());
        tag.putLong("Expires", expiresMs);
        tag.putLong("Granted", grantedMs);
        return tag;
    }

    public static AccessGrant load(CompoundTag tag) {
        return new AccessGrant(tag.getUUID("Player"), Kind.valueOf(tag.getString("Kind")), tag.getString("Target"), Tier.valueOf(tag.getString("Tier")),
                tag.getLong("Expires"), tag.getLong("Granted"));
    }
}
