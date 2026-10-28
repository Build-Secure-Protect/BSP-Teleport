package com.mrgregles.bsp_teleport.access;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T82: daily open hours (D150 to D158). */
class OpenHoursTest {
    private static int t(String hhmm) {
        return OpenHours.time(hhmm, false);
    }

    @Test
    void the_owners_example_is_open_in_both_windows_only() {
        OpenHours h = OpenHours.parse("15:00-17:00, 03:00-05:00");
        assertNotNull(h);
        assertEquals("03:00-05:00,15:00-17:00", h.encode());
        assertTrue(h.isOpen(t("16:10")));
        assertTrue(h.isOpen(t("04:30")));
        assertFalse(h.isOpen(t("17:00")));
        assertFalse(h.isOpen(t("20:00")));
        assertEquals(t("17:00"), h.closesAt(t("16:10")));
        assertEquals(t("03:00"), h.nextOpen(t("20:00")));
        assertEquals(t("15:00"), h.nextOpen(t("05:00")));
        assertEquals(-1, h.nextOpen(t("15:00")));
    }

    @Test
    void a_window_may_cross_midnight() {
        OpenHours h = OpenHours.parse("22:00-02:00");
        assertNotNull(h);
        assertTrue(h.isOpen(t("23:59")) && h.isOpen(t("00:00")) && h.isOpen(t("01:59")));
        assertFalse(h.isOpen(t("02:00")));
        assertEquals(t("02:00"), h.closesAt(t("23:00")));
        assertEquals(t("22:00"), h.nextOpen(t("12:00")));
    }

    @Test
    void touching_and_overlapping_windows_are_joined() {
        assertEquals("15:00-17:00", OpenHours.parse("15:00-16:00,16:00-17:00").encode());
        assertEquals("14:00-18:00", OpenHours.parse("15:00-17:00 14:00-16:30 16:00-18:00").encode());
        // across midnight, joined with the window that starts at midnight
        assertEquals("22:00-03:00", OpenHours.parse("22:00-24:00,00:00-03:00").encode());
        assertEquals("00:00-24:00", OpenHours.parse("00:00-12:00,12:00-24:00").encode());
    }

    @Test
    void bad_input_is_refused() {
        assertNull(OpenHours.parse("15:00-15:00"));
        assertNull(OpenHours.parse("25:00-26:00"));
        assertNull(OpenHours.parse("15:00"));
        assertNull(OpenHours.parse("3pm-5pm"));
        assertNull(OpenHours.parse(""));
        assertNull(OpenHours.parse("24:00-02:00"));
        assertNull(OpenHours.parse("01:00-02:00,03:00-04:00,05:00-06:00,07:00-08:00,09:00-10:00"), "more than four");
        assertNotNull(OpenHours.parse("01:00-02:00,03:00-04:00,05:00-06:00,07:00-08:00,08:00-10:00"), "four once joined");
    }

    @Test
    void the_old_command_form_reads_as_one_window() {
        assertEquals("15:00-17:00", OpenHours.parse("15:00 17:00").encode());
        assertEquals("03:00-05:00,15:00-17:00", OpenHours.parse("15:00 17:00 03:00 05:00").encode());
    }

    @Test
    void adding_and_removing_windows() {
        OpenHours h = OpenHours.parse("15:00-17:00");
        OpenHours more = h.plus(OpenHours.parse("03:00-05:00"));
        assertEquals("03:00-05:00,15:00-17:00", more.encode());
        assertEquals("15:00-17:00", more.without(0).encode());
        assertNull(h.without(0), "the last window cannot go");
        assertEquals(OpenHours.parse(more.encode()).encode(), more.encode());
    }

    @Test
    void all_day_never_closes() {
        OpenHours h = OpenHours.ALL_DAY;
        assertTrue(h.isOpen(0) && h.isOpen(1439));
        assertEquals(-1, h.closesAt(600));
        assertEquals("00:00-24:00", OpenHours.parse(h.encode()).encode());
    }
}
