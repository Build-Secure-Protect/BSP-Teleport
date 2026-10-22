package com.mrgregles.bsp_teleport;

import com.mrgregles.bsp_teleport.portal.PortalNames;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** D129: default portal names are {@code <server>-<n>} with one counter for the whole network. */
class PortalNamesTest {
    @Test
    void the_trailing_number_is_read_from_names_that_end_in_dash_digits() {
        assertEquals(3, PortalNames.trailingNumber("BSP-Spawn-3"));
        assertEquals(12, PortalNames.trailingNumber("BSP-Frostwarf-12"));
        assertEquals(0, PortalNames.trailingNumber("Portal"));
        assertEquals(0, PortalNames.trailingNumber("BSP-Spawn"));
        assertEquals(0, PortalNames.trailingNumber("Market Gate-"));
        assertEquals(0, PortalNames.trailingNumber("Gate-2b"));
    }

    @Test
    void a_name_is_the_server_and_the_number_and_never_longer_than_32() {
        assertEquals("BSP-Frostwarf-2", PortalNames.compose("BSP-Frostwarf", 2));
        assertEquals("Portal-1", PortalNames.compose("", 1));
        String longName = PortalNames.compose("BSP-A-Very-Long-Server-Name-Indeed", 105);
        assertEquals(32, longName.length());
        assertEquals(105, PortalNames.trailingNumber(longName));
    }
}
