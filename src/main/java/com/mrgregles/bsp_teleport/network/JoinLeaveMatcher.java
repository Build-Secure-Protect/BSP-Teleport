package com.mrgregles.bsp_teleport.network;

/**
 * Decides whether a system line is vanilla's join or leave announcement for one player. Pure string logic, so it is
 * JUnit-tested; {@link JoinLeaveMessages} feeds it the server's language templates and the player's names.
 *
 * <p>Vanilla uses three templates: {@code multiplayer.player.joined} ("%s joined the game"),
 * {@code multiplayer.player.joined.renamed} ("%s (formerly known as %s) joined the game") and
 * {@code multiplayer.player.left} ("%s left the game"). Forge broadcasts them as translatable components; Mohist resolves
 * them to text first (through Bukkit's join and quit message strings), so matching works on the plain text either way.
 */
public final class JoinLeaveMatcher {
    private JoinLeaveMatcher() {
    }

    /**
     * @param plain      the line's plain text (formatting codes already stripped)
     * @param names      the player's names to accept: profile name and display name
     * @param joined     the {@code multiplayer.player.joined} template, with its {@code %s}
     * @param renamed    the {@code multiplayer.player.joined.renamed} template, with two {@code %s}
     * @param left       the {@code multiplayer.player.left} template, with its {@code %s}
     * @return whether the line is that player's join or leave announcement
     */
    public static boolean matches(String plain, Iterable<String> names, String joined, String renamed, String left) {
        if (plain == null) {
            return false;
        }
        String line = plain.strip();
        if (line.isEmpty()) {
            return false;
        }
        for (String name : names) {
            if (name == null || name.isEmpty()) {
                continue;
            }
            if (line.equals(fill(joined, name)) || line.equals(fill(left, name)) || matchesRenamed(line, renamed, name)) {
                return true;
            }
        }
        return false;
    }

    /** Fills a one-placeholder template; {@code %1$s} is accepted too. */
    static String fill(String template, String name) {
        if (template == null) {
            return null;
        }
        return template.replace("%1$s", "%s").replace("%s", name);
    }

    /** "%s (formerly known as %s) joined the game": the first placeholder is the player, the second may be anything. */
    static boolean matchesRenamed(String line, String renamed, String name) {
        if (renamed == null) {
            return false;
        }
        String t = renamed.replace("%1$s", "%s").replace("%2$s", "%s");
        String[] parts = t.split("%s", -1);
        if (parts.length != 3) {
            return false;
        }
        String head = parts[0] + name + parts[1];
        if (!line.startsWith(head) || !line.endsWith(parts[2])) {
            return false;
        }
        // The old name must be at least one character long.
        return line.length() > head.length() + parts[2].length();
    }
}
