package com.mrgregles.bsp_teleport.portal;

import com.mrgregles.bsp_teleport.data.Position;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;

/**
 * Where a portal sends players (D55): one of five kinds.
 *
 * @param target   the warp name, the server for a random teleport (empty for this server), or the link id
 * @param position the exact place for {@link Kind#POSITION}, else null
 */
public record PortalDestination(Kind kind, String target, @Nullable Position position) {
    public enum Kind { NONE, WARP, PORTAL_WARP, RANDOM, POSITION, LINK }

    public static final PortalDestination NONE = new PortalDestination(Kind.NONE, "", null);

    public static PortalDestination warp(String name, boolean portalWarp) {
        return new PortalDestination(portalWarp ? Kind.PORTAL_WARP : Kind.WARP, name, null);
    }

    public static PortalDestination random(String server) {
        return new PortalDestination(Kind.RANDOM, server, null);
    }

    public static PortalDestination position(Position position) {
        return new PortalDestination(Kind.POSITION, "", position);
    }

    public static PortalDestination link(String id) {
        return new PortalDestination(Kind.LINK, id, null);
    }

    public boolean isSet() {
        return kind != Kind.NONE;
    }

    public boolean isLink() {
        return kind == Kind.LINK;
    }

    /** Plain text for admins: "warp shop", "random on BSP-Wilds", "link hub-east", ... */
    public String describe() {
        return switch (kind) {
            case NONE -> "-";
            case WARP -> "warp " + target;
            case PORTAL_WARP -> "portal warp " + target;
            case RANDOM -> target.isEmpty() ? "random here" : "random on " + target;
            case POSITION -> position == null ? "position ?" : "position " + position.describe();
            case LINK -> "link " + target;
        };
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Kind", kind.name());
        tag.putString("Target", target);
        if (position != null) {
            tag.put("Position", position.save());
        }
        return tag;
    }

    public static PortalDestination load(CompoundTag tag) {
        Kind kind;
        try {
            kind = Kind.valueOf(tag.getString("Kind"));
        } catch (IllegalArgumentException e) {
            kind = Kind.NONE;
        }
        return new PortalDestination(kind, tag.getString("Target"), tag.contains("Position") ? Position.load(tag.getCompound("Position")) : null);
    }

    /** {@code kind|target|position}; the position uses its own separators. */
    public String serialize() {
        return kind.name() + "|" + target + "|" + (position == null ? "" : position.serialize());
    }

    public static PortalDestination parse(String text) {
        String[] p = text.split("\\|", 3);
        if (p.length < 2) {
            return NONE;
        }
        Kind kind;
        try {
            kind = Kind.valueOf(p[0]);
        } catch (IllegalArgumentException e) {
            return NONE;
        }
        Position pos = p.length > 2 && !p[2].isEmpty() ? Position.parse(p[2]) : null;
        return new PortalDestination(kind, p[1], pos);
    }
}
