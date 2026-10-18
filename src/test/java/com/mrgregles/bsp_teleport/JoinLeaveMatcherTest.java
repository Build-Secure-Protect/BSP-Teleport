package com.mrgregles.bsp_teleport;

import com.mrgregles.bsp_teleport.network.JoinLeaveMatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure text rule behind network.suppressJoinLeaveMessages, with vanilla's en_us templates. */
class JoinLeaveMatcherTest {
    private static final String JOINED = "%s joined the game";
    private static final String RENAMED = "%s (formerly known as %s) joined the game";
    private static final String LEFT = "%s left the game";
    private static final List<String> NAMES = List.of("Greg", "Greg");

    private static boolean matches(String line) {
        return JoinLeaveMatcher.matches(line, NAMES, JOINED, RENAMED, LEFT);
    }

    @Test
    void vanillaJoinAndLeaveLinesMatch() {
        assertTrue(matches("Greg joined the game"));
        assertTrue(matches("Greg left the game"));
        assertTrue(matches("Greg (formerly known as OldGreg) joined the game"));
        assertTrue(matches("  Greg joined the game "), "Mohist's text form may carry whitespace");
    }

    @Test
    void otherLinesPass() {
        assertFalse(matches("Welcome, Greg!"));
        assertFalse(matches("Greg joined the game!"));
        assertFalse(matches("Gregory joined the game"), "another player's line is not ours");
        assertFalse(matches("Someone else left the game"));
        assertFalse(matches("Greg (formerly known as ) joined the game"), "an empty old name is not vanilla's line");
        assertFalse(matches(""));
        assertFalse(matches(null));
    }

    @Test
    void displayNameCountsToo() {
        assertTrue(JoinLeaveMatcher.matches("[Admin] Greg joined the game", List.of("Greg", "[Admin] Greg"), JOINED, RENAMED, LEFT));
        assertFalse(JoinLeaveMatcher.matches("[Admin] Greg joined the game", List.of("Greg"), JOINED, RENAMED, LEFT));
    }

    @Test
    void indexedPlaceholdersWork() {
        assertTrue(JoinLeaveMatcher.matches("Greg joined the game", NAMES, "%1$s joined the game", "%1$s (formerly known as %2$s) joined the game", "%1$s left the game"));
        assertTrue(JoinLeaveMatcher.matches("Greg (formerly known as X) joined the game", NAMES, "%1$s joined the game", "%1$s (formerly known as %2$s) joined the game", "%1$s left the game"));
    }
}
