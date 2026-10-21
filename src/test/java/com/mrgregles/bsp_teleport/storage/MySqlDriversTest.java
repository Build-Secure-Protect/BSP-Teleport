package com.mrgregles.bsp_teleport.storage;

import com.mrgregles.bsp_teleport.compat.BspCore;
import org.junit.jupiter.api.Test;

import java.sql.Driver;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B8: on plain Forge the only MySQL driver is the relocated one in BSP-Core's full jar. */
class MySqlDriversTest {
    @Test
    void bsp_cores_driver_is_tried_after_the_servers_own_and_before_mariadb() {
        String[] order = Arrays.stream(MySqlStore.DRIVERS).map(d -> d[0]).toArray(String[]::new);
        assertEquals(BspCore.MYSQL_DRIVER, order[1]);
        assertEquals("com.mysql.cj.jdbc.Driver", order[0]);
        assertEquals("org.mariadb.jdbc.Driver", order[2]);
    }

    /** Fails when BSP-Core renames or drops its relocated driver, so the name here goes stale at build time, not on a server. */
    @Test
    void bsp_cores_full_jar_has_the_driver_under_that_name() throws Exception {
        Driver driver = (Driver) Class.forName(BspCore.MYSQL_DRIVER).getDeclaredConstructor().newInstance();
        assertTrue(driver.acceptsURL("jdbc:mysql://localhost:3306/bsp"));
    }
}
