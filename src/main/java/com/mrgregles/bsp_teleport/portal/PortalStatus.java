package com.mrgregles.bsp_teleport.portal;

/** What a Portal Core reports about its frame and destination, in the order the checks run. */
public enum PortalStatus {
    /** No frame block touches the core. */
    NO_FRAME,
    /** The frame does not enclose any air. */
    OPEN_FRAME,
    /** Frame blocks are not all in one vertical plane. */
    NOT_FLAT,
    /** Another core touches the same frame. */
    TWO_CORES,
    /** The frame is wider or taller than {@code portals.maxSize}, or the opening has more cells than {@code portals.maxOpening}. */
    TOO_BIG,
    /** Something other than air (or the portal's own field and doors) sits inside the opening. */
    BLOCKED,
    /** The frame is fine but the core has no destination. */
    NO_DESTINATION,
    /** The warp or place the core points at no longer exists (or random teleport is off here). */
    MISSING,
    /** Two older cores already share the core's link id (D55: exactly two). */
    LINK_IN_USE,
    /** The core's link id has no partner yet. */
    WAITING,
    /** The destination's server is not up. */
    DOWN,
    /** Frame and destination are both fine: the portal lights up when it is open. */
    READY;

    /** The lang key for the player-facing line. */
    public String key() {
        return "message.bsp_teleport.portal.status." + name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Whether the frame itself is usable (the destination may still be missing). */
    public boolean frameOk() {
        return ordinal() >= NO_DESTINATION.ordinal();
    }
}
