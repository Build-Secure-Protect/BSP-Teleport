package com.mrgregles.bsp_teleport.storage;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the network database and Redis are, and their passwords: {@code config/bsp_teleport-network.toml}, a COMMON config. Forge
 * sends a server's SERVER config file (bsp_teleport-server.toml) to every player who joins, so these settings must never be in it
 * (D149). The old keys are still in the server file as placeholders: a value found there is moved here ({@link #moveFromServerConfig}).
 */
public final class NetworkConfig {
    public static final String FILE = "bsp_teleport-network.toml";

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> DB_HOST, DB_DATABASE, DB_USER, DB_PASSWORD, DB_TABLE_PREFIX, REDIS_HOST, REDIS_PASSWORD;
    public static final ForgeConfigSpec.IntValue DB_PORT, REDIS_PORT, REDIS_DB;
    public static final ForgeConfigSpec.BooleanValue DB_SSL, REDIS_SSL;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("BSP-Teleport's network connection: the MySQL or MariaDB database and Redis that every server of a network shares.",
                        "Used only when storage.mode in the server config (bsp_teleport-server.toml) is \"mysql\".",
                        "This file stays on the server: unlike the server config it is never sent to players. Keep it private,",
                        "and leave it out of any client pack.")
                .push("database");
        DB_HOST = b.comment("Database host name or address.").define("host", "localhost");
        DB_PORT = b.comment("Database port.").defineInRange("port", 3306, 1, 65535);
        DB_DATABASE = b.comment("Name of the existing database (schema) to use. BSP-Core's can be shared: the tables are prefixed.").define("database", "bsp");
        DB_USER = b.comment("Database user. It needs CREATE, SELECT, INSERT, UPDATE and DELETE on that database.").define("user", "bsp");
        DB_PASSWORD = b.comment("Database password.").define("password", "");
        DB_TABLE_PREFIX = b.comment("Prefix for this mod's tables. Letters, digits and underscores only.").define("tablePrefix", "bsptp_");
        DB_SSL = b.comment("Connect with SSL/TLS.").define("useSsl", false);
        b.pop();
        b.comment("Redis, optional (switched on with redis.enabled in the server config).").push("redis");
        REDIS_HOST = b.define("host", "localhost");
        REDIS_PORT = b.defineInRange("port", 6379, 1, 65535);
        REDIS_PASSWORD = b.comment("Password of the Redis default user, or empty.").define("password", "");
        REDIS_SSL = b.comment("Connect with TLS.").define("useSsl", false);
        REDIS_DB = b.comment("Redis database index.").defineInRange("db", 0, 0, 15);
        b.pop();
        SPEC = b.build();
    }

    private NetworkConfig() {
    }

    /** One setting that moved: its old place in the server config and its new one here. */
    private record Moved<T>(String name, ForgeConfigSpec.ConfigValue<T> old, ForgeConfigSpec.ConfigValue<T> now, boolean secret) {
        /** Copies a value set in the old place over, unless one is already set here; then clears the old place. Null when nothing was there. */
        String move() {
            T was = old.get();
            if (was.equals(old.getDefault())) {
                return null;
            }
            String what;
            if (now.get().equals(now.getDefault())) {
                now.set(was);
                what = name + " moved";
            } else {
                what = now.get().equals(was) ? name + " already here" : name + " already set here, the old value was dropped";
            }
            old.set(old.getDefault());
            return secret ? what : what + " (" + was + ")";
        }
    }

    private static List<Moved<?>> moved() {
        return List.of(
                new Moved<>("database host", TeleportConfig.OLD_STORAGE_HOST, DB_HOST, false),
                new Moved<>("database port", TeleportConfig.OLD_STORAGE_PORT, DB_PORT, false),
                new Moved<>("database name", TeleportConfig.OLD_STORAGE_DATABASE, DB_DATABASE, false),
                new Moved<>("database user", TeleportConfig.OLD_STORAGE_USER, DB_USER, false),
                new Moved<>("database password", TeleportConfig.OLD_STORAGE_PASSWORD, DB_PASSWORD, true),
                new Moved<>("table prefix", TeleportConfig.OLD_STORAGE_TABLE_PREFIX, DB_TABLE_PREFIX, false),
                new Moved<>("database SSL", TeleportConfig.OLD_STORAGE_SSL, DB_SSL, false),
                new Moved<>("Redis host", TeleportConfig.OLD_REDIS_HOST, REDIS_HOST, false),
                new Moved<>("Redis port", TeleportConfig.OLD_REDIS_PORT, REDIS_PORT, false),
                new Moved<>("Redis password", TeleportConfig.OLD_REDIS_PASSWORD, REDIS_PASSWORD, true),
                new Moved<>("Redis TLS", TeleportConfig.OLD_REDIS_SSL, REDIS_SSL, false),
                new Moved<>("Redis database", TeleportConfig.OLD_REDIS_DB, REDIS_DB, false));
    }

    /**
     * At every start, before anyone can join, and after {@code /bsptp reload}: the connection settings still in the server config
     * are copied here (a value already set here wins) and cleared there, and both files are saved. Returns what moved, for the
     * log; passwords are never written to it.
     */
    public static List<String> moveFromServerConfig() {
        List<String> done = new ArrayList<>();
        for (Moved<?> m : moved()) {
            String what = m.move();
            if (what != null) {
                done.add(what);
            }
        }
        if (!done.isEmpty()) {
            SPEC.save();
            TeleportConfig.SPEC.save();
            BSPTeleport.LOGGER.warn("BSP-Teleport storage: the network connection settings were in bsp_teleport-server.toml, which is sent to every player who joins. "
                    + "They are now in config/{} and cleared from the server config: {}. If they are also in defaultconfigs/bsp_teleport-server.toml, clear them there too.",
                    FILE, String.join(", ", done));
        }
        return done;
    }

    /** The server config is loaded and nobody can have joined yet. */
    public static void onAboutToStart(net.minecraftforge.event.server.ServerAboutToStartEvent event) {
        try {
            moveFromServerConfig();
        } catch (RuntimeException e) {
            BSPTeleport.LOGGER.error("BSP-Teleport storage: could not move the connection settings out of bsp_teleport-server.toml: {}", e.toString());
        }
    }
}
