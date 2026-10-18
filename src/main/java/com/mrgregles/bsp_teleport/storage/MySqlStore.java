package com.mrgregles.bsp_teleport.storage;

import com.mrgregles.bsp_teleport.api.Destination;
import com.mrgregles.bsp_teleport.data.Home;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.data.PortalRow;
import com.mrgregles.bsp_teleport.data.Warp;
import com.mrgregles.bsp_teleport.portal.PortalDestination;

import javax.annotation.Nullable;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * The shared MySQL (or MariaDB) tables. Every method blocks and runs on the storage worker thread only.
 *
 * <p>No driver is bundled: the server's own (Mohist ships one; BSP-Core's full jar ships one for dev runs) is created
 * directly, because {@code DriverManager} refuses drivers loaded by a mod class loader. The mod only creates and uses
 * its own tables, all named with the configured prefix, in a database the owner provides.
 */
final class MySqlStore implements AutoCloseable {
    private final String host, database, user, password, prefix;
    private final int port;
    private final boolean ssl;
    @Nullable
    private Connection connection;

    MySqlStore(String host, int port, String database, String user, String password, String prefix, boolean ssl) {
        if (!prefix.matches("[A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("storage.tablePrefix may only contain letters, digits and underscores");
        }
        this.host = host;
        this.port = port;
        this.database = database;
        this.user = user;
        this.password = password;
        this.prefix = prefix;
        this.ssl = ssl;
    }

    String describe() {
        return user + "@" + host + ":" + port + "/" + database + " (tables " + prefix + "*)";
    }

    private String t(String table) {
        return "`" + prefix + table + "`";
    }

    private Connection open() throws SQLException {
        String[][] drivers = {{"com.mysql.cj.jdbc.Driver", "jdbc:mysql://"}, {"org.mariadb.jdbc.Driver", "jdbc:mariadb://"}};
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        SQLException last = null;
        for (String[] d : drivers) {
            Driver driver;
            try {
                driver = (Driver) Class.forName(d[0]).getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | LinkageError notInstalled) {
                continue;
            }
            String url = d[1] + host + ":" + port + "/" + database + "?useSSL=" + ssl + "&allowPublicKeyRetrieval=true&characterEncoding=utf8"
                    + "&connectTimeout=5000&socketTimeout=15000";
            try {
                Connection c = driver.connect(url, props);
                if (c != null) {
                    return c;
                }
            } catch (SQLException e) {
                last = e;
            }
        }
        throw last != null ? last : new SQLException("No MySQL or MariaDB JDBC driver is available to BSP-Teleport");
    }

    private Connection conn() throws SQLException {
        if (connection == null || !connection.isValid(2)) {
            close();
            connection = open();
        }
        return connection;
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
            connection = null;
        }
    }

    void init() throws SQLException {
        try (Statement st = conn().createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + t("users") + " (uuid CHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(16) NOT NULL DEFAULT '', "
                    + "cooldown_until BIGINT NOT NULL DEFAULT 0, back VARCHAR(255) NULL, offline VARCHAR(255) NULL, ignoring BOOLEAN NOT NULL DEFAULT FALSE, "
                    + "last_server VARCHAR(64) NOT NULL DEFAULT '', updated_ms BIGINT NOT NULL DEFAULT 0, INDEX (name))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + t("homes") + " (owner CHAR(36) NOT NULL, name VARCHAR(16) NOT NULL, position VARCHAR(255) NOT NULL, "
                    + "created_ms BIGINT NOT NULL, description VARCHAR(255) NOT NULL DEFAULT '', public BOOLEAN NOT NULL DEFAULT FALSE, icon VARCHAR(96) NOT NULL DEFAULT '', PRIMARY KEY (owner, name), INDEX (public))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + t("warps") + " (name VARCHAR(16) NOT NULL PRIMARY KEY, position VARCHAR(255) NOT NULL, "
                    + "cost INT NOT NULL DEFAULT 0, restricted BOOLEAN NOT NULL DEFAULT FALSE, icon VARCHAR(96) NOT NULL DEFAULT '', portal BOOLEAN NOT NULL DEFAULT FALSE)");
            addColumn(st, "warps", "portal", "BOOLEAN NOT NULL DEFAULT FALSE");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + t("portals") + " (id VARCHAR(190) NOT NULL PRIMARY KEY, server VARCHAR(64) NOT NULL, world VARCHAR(128) NOT NULL, "
                    + "x INT NOT NULL, y INT NOT NULL, z INT NOT NULL, name VARCHAR(32) NOT NULL DEFAULT '', destination VARCHAR(512) NOT NULL DEFAULT '', "
                    + "arrive_only BOOLEAN NOT NULL DEFAULT FALSE, exit_to VARCHAR(512) NOT NULL DEFAULT '', frame_ok BOOLEAN NOT NULL DEFAULT FALSE, "
                    + "colour VARCHAR(16) NOT NULL DEFAULT '', status VARCHAR(24) NOT NULL DEFAULT '', doors VARCHAR(16) NOT NULL DEFAULT '', created_ms BIGINT NOT NULL DEFAULT 0, INDEX (server))");
            addColumn(st, "portals", "doors", "VARCHAR(16) NOT NULL DEFAULT ''");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + t("spawns") + " (server VARCHAR(64) NOT NULL PRIMARY KEY, position VARCHAR(255) NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + t("pending") + " (uuid CHAR(36) NOT NULL PRIMARY KEY, destination VARCHAR(512) NOT NULL, created_ms BIGINT NOT NULL)");
        }
    }

    /** Adds a column to a table made by an older version; MariaDB knows IF NOT EXISTS, MySQL answers "duplicate column", which is fine. */
    private void addColumn(Statement st, String table, String column, String definition) {
        try {
            st.executeUpdate("ALTER TABLE " + t(table) + " ADD COLUMN IF NOT EXISTS " + column + " " + definition);
        } catch (SQLException e) {
            try {
                st.executeUpdate("ALTER TABLE " + t(table) + " ADD COLUMN " + column + " " + definition);
            } catch (SQLException ignored) {
                // already there
            }
        }
    }

    // Users

    record UserRow(UUID uuid, String name, long cooldownUntil, @Nullable Position back, @Nullable Position offline, boolean ignoring) {
    }

    @Nullable
    UserRow loadUser(UUID uuid) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("SELECT uuid, name, cooldown_until, back, offline, ignoring FROM " + t("users") + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readUser(rs) : null;
            }
        }
    }

    /** The most recently seen user with this name, ignoring case. */
    @Nullable
    UserRow findUser(String name) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("SELECT uuid, name, cooldown_until, back, offline, ignoring FROM " + t("users")
                + " WHERE LOWER(name) = LOWER(?) ORDER BY updated_ms DESC LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readUser(rs) : null;
            }
        }
    }

    private static UserRow readUser(ResultSet rs) throws SQLException {
        String back = rs.getString(4);
        String offline = rs.getString(5);
        return new UserRow(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3),
                back == null || back.isEmpty() ? null : Position.parse(back),
                offline == null || offline.isEmpty() ? null : Position.parse(offline), rs.getBoolean(6));
    }

    void saveOffline(UUID uuid, Position offline) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + t("users") + " (uuid, offline, updated_ms) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE offline = VALUES(offline), updated_ms = VALUES(updated_ms)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, offline.serialize());
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    void saveIgnoring(UUID uuid, boolean ignoring) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + t("users") + " (uuid, ignoring, updated_ms) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE ignoring = VALUES(ignoring), updated_ms = VALUES(updated_ms)")) {
            ps.setString(1, uuid.toString());
            ps.setBoolean(2, ignoring);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    void touchUser(UUID uuid, String name, String server) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + t("users") + " (uuid, name, last_server, updated_ms) VALUES (?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE name = VALUES(name), last_server = VALUES(last_server), updated_ms = VALUES(updated_ms)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, server);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    void saveCooldown(UUID uuid, long until) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + t("users") + " (uuid, cooldown_until, updated_ms) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE cooldown_until = VALUES(cooldown_until), updated_ms = VALUES(updated_ms)")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, until);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    void saveBack(UUID uuid, Position back) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + t("users") + " (uuid, back, updated_ms) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE back = VALUES(back), updated_ms = VALUES(updated_ms)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, back.serialize());
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    // Homes

    List<Home> loadHomes(UUID owner) throws SQLException {
        List<Home> homes = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement("SELECT name, position, created_ms, description, public, icon FROM " + t("homes") + " WHERE owner = ? ORDER BY created_ms, name")) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    homes.add(new Home(rs.getString(1), Position.parse(rs.getString(2)), rs.getLong(3), rs.getString(4), rs.getBoolean(5), rs.getString(6)));
                }
            }
        }
        return homes;
    }

    /** Every public home with its owner's name: {@code (ownerName, home)} pairs. */
    List<PublicHome> loadPublicHomes() throws SQLException {
        List<PublicHome> homes = new ArrayList<>();
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery("SELECT h.owner, u.name, h.name, h.position, h.created_ms, h.description, h.icon FROM "
                + t("homes") + " h LEFT JOIN " + t("users") + " u ON u.uuid = h.owner WHERE h.public = TRUE ORDER BY u.name, h.name")) {
            while (rs.next()) {
                String owner = rs.getString(2);
                homes.add(new PublicHome(UUID.fromString(rs.getString(1)), owner == null ? "?" : owner,
                        new Home(rs.getString(3), Position.parse(rs.getString(4)), rs.getLong(5), rs.getString(6), true, rs.getString(7))));
            }
        }
        return homes;
    }

    record PublicHome(UUID owner, String ownerName, Home home) {
    }

    void saveHome(UUID owner, Home home) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + t("homes") + " (owner, name, position, created_ms, description, public, icon) VALUES (?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE position = VALUES(position), description = VALUES(description), public = VALUES(public), icon = VALUES(icon)")) {
            ps.setString(1, owner.toString());
            ps.setString(2, home.name());
            ps.setString(3, home.position().serialize());
            ps.setLong(4, home.createdAt());
            ps.setString(5, home.description());
            ps.setBoolean(6, home.isPublic());
            ps.setString(7, home.icon());
            ps.executeUpdate();
        }
    }

    void deleteHome(UUID owner, String name) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("DELETE FROM " + t("homes") + " WHERE owner = ? AND name = ?")) {
            ps.setString(1, owner.toString());
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    void deleteAllHomes() throws SQLException {
        try (Statement st = conn().createStatement()) {
            st.executeUpdate("DELETE FROM " + t("homes"));
        }
    }

    // Warps

    List<Warp> loadWarps() throws SQLException {
        List<Warp> warps = new ArrayList<>();
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery("SELECT name, position, cost, restricted, icon, portal FROM " + t("warps") + " ORDER BY name")) {
            while (rs.next()) {
                warps.add(new Warp(rs.getString(1), Position.parse(rs.getString(2)), rs.getInt(3), rs.getBoolean(4), rs.getString(5), rs.getBoolean(6)));
            }
        }
        return warps;
    }

    void saveWarp(Warp warp) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + t("warps") + " (name, position, cost, restricted, icon, portal) VALUES (?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE position = VALUES(position), cost = VALUES(cost), restricted = VALUES(restricted), icon = VALUES(icon), portal = VALUES(portal)")) {
            ps.setString(1, warp.name());
            ps.setString(2, warp.position().serialize());
            ps.setInt(3, warp.cost());
            ps.setBoolean(4, warp.restricted());
            ps.setString(5, warp.icon());
            ps.setBoolean(6, warp.portal());
            ps.executeUpdate();
        }
    }

    // Portals

    List<PortalRow> loadPortals() throws SQLException {
        List<PortalRow> rows = new ArrayList<>();
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery("SELECT id, server, world, x, y, z, name, destination, arrive_only, exit_to, frame_ok, colour, status, doors, created_ms FROM " + t("portals") + " ORDER BY created_ms")) {
            while (rs.next()) {
                String exit = rs.getString(10);
                rows.add(new PortalRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getString(7),
                        PortalDestination.parse(rs.getString(8)), rs.getBoolean(9), exit.isEmpty() ? null : Destination.parse(exit), rs.getBoolean(11),
                        rs.getString(12), rs.getString(13), rs.getString(14), rs.getLong(15)));
            }
        }
        return rows;
    }

    void savePortal(PortalRow row) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + t("portals") + " (id, server, world, x, y, z, name, destination, arrive_only, exit_to, frame_ok, colour, status, doors, created_ms) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE name = VALUES(name), destination = VALUES(destination), arrive_only = VALUES(arrive_only), "
                + "exit_to = VALUES(exit_to), frame_ok = VALUES(frame_ok), colour = VALUES(colour), status = VALUES(status), doors = VALUES(doors)")) {
            ps.setString(1, row.id());
            ps.setString(2, row.server());
            ps.setString(3, row.world());
            ps.setInt(4, row.x());
            ps.setInt(5, row.y());
            ps.setInt(6, row.z());
            ps.setString(7, row.name());
            ps.setString(8, row.destination().serialize());
            ps.setBoolean(9, row.arriveOnly());
            ps.setString(10, row.exit() == null ? "" : row.exit().serialize());
            ps.setBoolean(11, row.frameOk());
            ps.setString(12, row.colour());
            ps.setString(13, row.status());
            ps.setString(14, row.doors());
            ps.setLong(15, row.createdMs());
            ps.executeUpdate();
        }
    }

    void deletePortal(String id) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("DELETE FROM " + t("portals") + " WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    void deleteWarp(String name) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("DELETE FROM " + t("warps") + " WHERE name = ?")) {
            ps.setString(1, name);
            ps.executeUpdate();
        }
    }

    // Spawn

    @Nullable
    Position loadSpawn(String server) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("SELECT position FROM " + t("spawns") + " WHERE server = ?")) {
            ps.setString(1, server);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Position.parse(rs.getString(1)) : null;
            }
        }
    }

    void saveSpawn(String server, Position spawn) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + t("spawns") + " (server, position) VALUES (?, ?) "
                + "ON DUPLICATE KEY UPDATE position = VALUES(position)")) {
            ps.setString(1, server);
            ps.setString(2, spawn.serialize());
            ps.executeUpdate();
        }
    }

    // Pending cross-server landings

    void savePending(UUID uuid, Destination destination) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + t("pending") + " (uuid, destination, created_ms) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE destination = VALUES(destination), created_ms = VALUES(created_ms)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, destination.serialize());
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /** Reads and removes the player's pending landing; null when there is none or it is older than {@code maxAgeMs}. */
    @Nullable
    Destination takePending(UUID uuid, long maxAgeMs) throws SQLException {
        Destination destination = null;
        try (PreparedStatement ps = conn().prepareStatement("SELECT destination, created_ms FROM " + t("pending") + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && System.currentTimeMillis() - rs.getLong(2) <= maxAgeMs) {
                    destination = Destination.parse(rs.getString(1));
                }
            }
        }
        try (PreparedStatement ps = conn().prepareStatement("DELETE FROM " + t("pending") + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
        return destination;
    }

    void deleteOldPending(long maxAgeMs) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("DELETE FROM " + t("pending") + " WHERE created_ms < ?")) {
            ps.setLong(1, System.currentTimeMillis() - maxAgeMs);
            ps.executeUpdate();
        }
    }
}
