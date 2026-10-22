package com.mrgregles.bsp_teleport.portal;

/** The pure rules behind default portal names (D129), kept apart so they can be tested without a server. */
public final class PortalNames {
    /** Portal names are at most this long (the rename check and the table column). */
    public static final int MAX = 32;

    private PortalNames() {
    }

    /** The number after the last '-' of a name, or 0 when it does not end in "-digits". */
    public static int trailingNumber(String name) {
        int dash = name.lastIndexOf('-');
        if (dash < 0 || dash == name.length() - 1) {
            return 0;
        }
        String digits = name.substring(dash + 1);
        if (digits.length() > 9 || !digits.chars().allMatch(Character::isDigit)) {
            return 0;
        }
        return Integer.parseInt(digits);
    }

    /** {@code <server>-<n>}, the server name shortened when needed so the whole name fits in {@link #MAX}. */
    public static String compose(String server, int n) {
        String suffix = "-" + n;
        String base = server == null || server.isBlank() ? "Portal" : server.trim();
        if (base.length() + suffix.length() > MAX) {
            base = base.substring(0, MAX - suffix.length());
        }
        return base + suffix;
    }
}
