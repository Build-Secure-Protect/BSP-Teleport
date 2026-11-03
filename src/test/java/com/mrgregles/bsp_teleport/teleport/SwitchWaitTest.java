package com.mrgregles.bsp_teleport.teleport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** B38: a switch after a login waits for the client to be in the world, then the gap from that moment. */
class SwitchWaitTest {
    private static final long LOGIN = 1_000_000L;

    @Test
    void waitsInShortStepsWhileTheClientIsStillLoading() {
        assertEquals(TeleportService.READY_POLL_MS, TeleportService.switchWait(LOGIN + 8_000, LOGIN, null, 3));
    }

    @Test
    void countsTheGapFromWhenTheClientWasInTheWorld() {
        long ready = LOGIN + 12_000;
        assertEquals(10_000, TeleportService.switchWait(ready, LOGIN, ready, 10));
        assertEquals(4_000, TeleportService.switchWait(ready + 6_000, LOGIN, ready, 10));
        assertEquals(0, TeleportService.switchWait(ready + 10_000, LOGIN, ready, 10));
    }

    @Test
    void aPlayerLongInTheWorldGoesAtOnce() {
        assertEquals(0, TeleportService.switchWait(LOGIN + 60_000, LOGIN, LOGIN + 2_000, 3));
    }

    @Test
    void aClientThatNeverShowsUpIsMovedAfterTheTimeout() {
        long late = LOGIN + TeleportService.READY_TIMEOUT_MS;
        assertEquals(0, TeleportService.switchWait(late, LOGIN, null, 10));
    }
}
