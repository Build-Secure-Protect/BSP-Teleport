package com.mrgregles.bsp_teleport;

import com.mrgregles.bsp_teleport.team.TeamTeleports;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure parts of team teleports: the "seen" text and the member lookup. */
class TeamTeleportsTest {
    private static final long NOW = 1_700_000_000_000L;

    @Test
    void agoRoundsDownToTheLargestUnit() {
        assertEquals("", TeamTeleports.ago(0, NOW));
        assertEquals("just now", TeamTeleports.ago(NOW - 30_000, NOW));
        assertEquals("1 min ago", TeamTeleports.ago(NOW - 90_000, NOW));
        assertEquals("59 min ago", TeamTeleports.ago(NOW - 59 * 60_000, NOW));
        assertEquals("2 h ago", TeamTeleports.ago(NOW - 2 * 3_600_000 - 5_000, NOW));
        assertEquals("3 d ago", TeamTeleports.ago(NOW - 3 * 86_400_000L - 3_600_000, NOW));
        assertEquals("just now", TeamTeleports.ago(NOW + 60_000, NOW), "a clock ahead of ours is not the future");
    }

    @Test
    void findMatchesByUuidAndOnlineMeansHereOrOnAServer() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        TeamTeleports.Mate here = new TeamTeleports.Mate(a, "Alex", true, true, "survival-1", NOW);
        TeamTeleports.Mate away = new TeamTeleports.Mate(b, "Sam", false, false, "hub", NOW);
        TeamTeleports.Mate off = new TeamTeleports.Mate(UUID.randomUUID(), "Riley", false, false, "", NOW - 7_200_000);
        List<TeamTeleports.Mate> mates = List.of(here, away, off);
        assertSame(away, TeamTeleports.find(mates, b));
        assertNull(TeamTeleports.find(mates, UUID.randomUUID()));
        assertTrue(here.online());
        assertTrue(away.online());
        assertFalse(off.online());
    }
}
