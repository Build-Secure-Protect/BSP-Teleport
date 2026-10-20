package com.mrgregles.bsp_teleport.portal;

import com.mrgregles.bsp_teleport.data.Position;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * One place a portal sends players (D55, D94): a portal holds a list of these, each with a label the admin may set.
 *
 * @param target   the warp name, the server for a random teleport (empty for this server), another portal's row id
 *                 ({@link Kind#PORTAL}, D95), or a link id (old rows, converted on load)
 * @param position the exact place for {@link Kind#POSITION}, else null
 * @param label    the admin's custom name, or empty for the target's own name (D96)
 */
public record PortalDestination(Kind kind, String target, @Nullable Position position, String label) {
    public enum Kind { NONE, WARP, PORTAL_WARP, RANDOM, POSITION, LINK, PORTAL }

    /** The most entries a portal may hold without the config saying more (D94). */
    public static final int DEFAULT_MAX = 8;

    public static final PortalDestination NONE = new PortalDestination(Kind.NONE, "", null, "");

    public PortalDestination {
        label = label == null ? "" : label.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    public PortalDestination(Kind kind, String target, @Nullable Position position) {
        this(kind, target, position, "");
    }

    public static PortalDestination portal(String rowId) {
        return new PortalDestination(Kind.PORTAL, rowId, null);
    }

    public PortalDestination withLabel(String label) {
        return new PortalDestination(kind, target, position, label);
    }

    public boolean isPortal() {
        return kind == Kind.PORTAL;
    }

    /** The label to show: the admin's, else {@code own} (the target's own name as the caller resolved it). */
    public String labelOr(String own) {
        return label.isEmpty() ? own : label;
    }

    /** Whether two entries point at the same place, whatever their labels. */
    public boolean samePlace(PortalDestination other) {
        return kind == other.kind && target.equalsIgnoreCase(other.target) && java.util.Objects.equals(position, other.position);
    }

    // Lists (D94): one line per entry, so names with any character but a newline are safe.

    public static String serializeAll(List<PortalDestination> list) {
        StringBuilder sb = new StringBuilder();
        for (PortalDestination d : list) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(d.serialize());
        }
        return sb.toString();
    }

    public static List<PortalDestination> parseAll(String text) {
        List<PortalDestination> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        for (String line : text.split("\n")) {
            PortalDestination d = parse(line);
            if (d.isSet()) {
                out.add(d);
            }
        }
        return out;
    }

    public static ListTag saveAll(List<PortalDestination> list) {
        ListTag tag = new ListTag();
        list.forEach(d -> tag.add(d.save()));
        return tag;
    }

    public static List<PortalDestination> loadAll(ListTag tag) {
        List<PortalDestination> out = new ArrayList<>();
        for (Tag t : tag) {
            PortalDestination d = load((CompoundTag) t);
            if (d.isSet()) {
                out.add(d);
            }
        }
        return out;
    }

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
            case PORTAL -> "portal " + target;
        };
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Kind", kind.name());
        tag.putString("Target", target);
        if (!label.isEmpty()) {
            tag.putString("Label", label);
        }
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
        return new PortalDestination(kind, tag.getString("Target"), tag.contains("Position") ? Position.load(tag.getCompound("Position")) : null, tag.getString("Label"));
    }

    /**
     * Tab-separated {@code kind, target, position, label}: a Portal target is a row id with {@code |} in it, so the
     * old {@code kind|target|position} form (still read) could not carry it. Labels never hold a tab (the constructor).
     */
    public String serialize() {
        return kind.name() + "\t" + target + "\t" + (position == null ? "" : position.serialize()) + "\t" + label;
    }

    public static PortalDestination parse(String text) {
        String[] p = text.indexOf('\t') >= 0 ? text.split("\t", 4) : text.split("\\|", 3);
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
        return new PortalDestination(kind, p[1], pos, p.length > 3 ? p[3] : "");
    }
}
