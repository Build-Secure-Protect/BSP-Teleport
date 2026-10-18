package com.mrgregles.bsp_teleport.access;

import java.util.ArrayList;
import java.util.List;

/**
 * One rule on a portal or warp (D86); every rule in the list must pass.
 *
 * @param value   the LuckPerms group, the item id, the permission node, or empty for COINS and TOKEN
 * @param count   the item count, or the coins charged per pass
 * @param consume ITEM only: whether the items are used up on the move
 */
public record AccessRule(Kind kind, String value, int count, boolean consume) {
    public enum Kind { RANK, ITEM, NODE, COINS, TOKEN }

    public static final int MAX = 8;

    /** A one-line description for screens: "rank Warden", "item minecraft:ender_pearl x4 (used up)", "coins 50", "token". */
    public String describe() {
        return switch (kind) {
            case RANK -> "rank " + value;
            case ITEM -> "item " + value + " x" + Math.max(1, count) + (consume ? " (used up)" : "");
            case NODE -> "node " + value;
            case COINS -> "coins " + count;
            case TOKEN -> "token";
        };
    }

    /** {@code kind|value|count|consume}; the list form joins rules with {@code ;}. */
    public String serialize() {
        return kind.name() + "|" + value + "|" + count + "|" + consume;
    }

    public static AccessRule parse(String text) {
        String[] p = text.split("\\|", 4);
        Kind kind;
        try {
            kind = Kind.valueOf(p[0]);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int count = 0;
        try {
            count = p.length > 2 ? Integer.parseInt(p[2]) : 0;
        } catch (NumberFormatException ignored) {
        }
        return new AccessRule(kind, p.length > 1 ? p[1] : "", count, p.length > 3 && Boolean.parseBoolean(p[3]));
    }

    public static String serializeList(List<AccessRule> rules) {
        StringBuilder sb = new StringBuilder();
        for (AccessRule r : rules) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(r.serialize());
        }
        return sb.toString();
    }

    public static List<AccessRule> parseList(String text) {
        List<AccessRule> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        for (String part : text.split(";")) {
            AccessRule r = parse(part);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    /** A rule from screen or command input, or null when it makes no sense. */
    public static AccessRule of(String kindWord, String value, int count, boolean consume) {
        Kind kind;
        try {
            kind = Kind.valueOf(kindWord.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        String v = value.trim();
        return switch (kind) {
            case RANK, NODE -> v.isEmpty() ? null : new AccessRule(kind, v, 0, false);
            case ITEM -> v.isEmpty() ? null : new AccessRule(kind, v, Math.max(1, count), consume);
            case COINS -> count <= 0 ? null : new AccessRule(kind, "", count, false);
            case TOKEN -> new AccessRule(kind, "", 0, false);
        };
    }
}
