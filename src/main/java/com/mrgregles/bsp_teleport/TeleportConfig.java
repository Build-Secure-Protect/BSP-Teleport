package com.mrgregles.bsp_teleport;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/** Server config: {@code <world>/serverconfig/bsp_teleport-server.toml}. */
public final class TeleportConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<String> SERVER_NAME;
    public static final ForgeConfigSpec.IntValue LANDING_SECONDS, SWITCH_GAP_SECONDS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SERVER_ALIASES;

    public static final ForgeConfigSpec.IntValue WARMUP_SECONDS, COOLDOWN_SECONDS, INVULNERABLE_SECONDS, SKIP_COOLDOWN_COINS;
    public static final ForgeConfigSpec.BooleanValue CANCEL_ON_DAMAGE, CANCEL_ON_MOVE;

    public static final ForgeConfigSpec.ConfigValue<String> STORAGE_MODE, STORAGE_HOST, STORAGE_DATABASE, STORAGE_USER, STORAGE_PASSWORD, STORAGE_TABLE_PREFIX;
    public static final ForgeConfigSpec.IntValue STORAGE_PORT;
    public static final ForgeConfigSpec.BooleanValue STORAGE_SSL;

    public static final ForgeConfigSpec.BooleanValue REDIS_ENABLED, REDIS_SSL;
    public static final ForgeConfigSpec.ConfigValue<String> REDIS_HOST, REDIS_PASSWORD, REDIS_CLUSTER;
    public static final ForgeConfigSpec.IntValue REDIS_PORT, REDIS_DB;

    public static final ForgeConfigSpec.ConfigValue<String> GLOBAL_SPAWN_WARP;
    public static final ForgeConfigSpec.IntValue RAID_HOME_DISTANCE, RAID_RANDOM_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue SOUNDS;

    public static final ForgeConfigSpec.IntValue HOME_SLOTS;

    public static final ForgeConfigSpec.BooleanValue RTP_ENABLED;
    public static final ForgeConfigSpec.IntValue RTP_MIN_RADIUS, RTP_MAX_RADIUS, RTP_TRIES, RTP_MIN_Y, RTP_MAX_Y;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BACK_RESTRICTED_WORLDS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> RTP_WORLDS, RTP_UNSAFE_BLOCKS;

    public static final ForgeConfigSpec.IntValue REQUEST_SECONDS;

    public static final ForgeConfigSpec.IntValue PORTAL_MAX_SIZE, PORTAL_MAX_OPENING;
    public static final ForgeConfigSpec.ConfigValue<String> PORTAL_TIME_ZONE;

    public static final ForgeConfigSpec.BooleanValue ACCESS_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> ACCESS_LOBBY;
    public static final ForgeConfigSpec.IntValue ACCESS_RENT_HOURS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("network");
        SERVER_NAME = b.comment("This server's name as the Velocity proxy knows it (velocity.toml [servers]). Used to tell cross-server teleports apart.")
                .define("serverName", "server-1");
        LANDING_SECONDS = b.comment("How long a cross-server landing waits for its player to arrive before it is forgotten.")
                .defineInRange("landingSeconds", 120, 10, 3600);
        SWITCH_GAP_SECONDS = b.comment("Seconds after a player's login before they may be moved to another server again (B5: a second proxy switch on top of a fresh one kicks the client). 0 turns the wait off.")
                .defineInRange("switchGapSeconds", 3, 0, 60);
        SERVER_ALIASES = b.comment("BSP-Core names servers by its storage.serverId. When that differs from the proxy name, map it here as \"bspCoreId=proxyName\".")
                .defineListAllowEmpty("serverAliases", List.of(), o -> o instanceof String);
        b.pop();

        b.push("teleport");
        WARMUP_SECONDS = b.comment("Seconds a player must wait, standing still, before a teleport happens. 0 for none.")
                .defineInRange("warmupSeconds", 5, 0, 120);
        COOLDOWN_SECONDS = b.comment("Seconds between two teleports by the same player. 0 for none.")
                .defineInRange("cooldownSeconds", 60, 0, 86400);
        CANCEL_ON_DAMAGE = b.comment("Cancel the warm-up when the player is hurt.").define("cancelOnDamage", true);
        CANCEL_ON_MOVE = b.comment("Cancel the warm-up when the player moves more than a block.").define("cancelOnMove", true);
        INVULNERABLE_SECONDS = b.comment("Seconds a player cannot be hurt after arriving. 0 for none.")
                .defineInRange("invulnerableSeconds", 0, 0, 60);
        SKIP_COOLDOWN_COINS = b.comment("Shatter Coin value (in Copper) a player may pay with /tpnow to skip a cooldown. 0 turns it off.")
                .defineInRange("skipCooldownCoins", 0, 0, 1_000_000);
        b.pop();

        b.push("storage");
        STORAGE_MODE = b.comment("\"local\" keeps homes and warps in this server's world save. \"mysql\" shares them across the network and is needed for cross-server teleports.")
                .define("mode", "local");
        STORAGE_HOST = b.comment("Database host name or address.").define("host", "localhost");
        STORAGE_PORT = b.comment("Database port.").defineInRange("port", 3306, 1, 65535);
        STORAGE_DATABASE = b.comment("Name of the existing database (schema) to use. BSP-Core's can be shared: the tables are prefixed.").define("database", "bsp");
        STORAGE_USER = b.comment("Database user. It needs CREATE, SELECT, INSERT, UPDATE and DELETE on that database.").define("user", "bsp");
        STORAGE_PASSWORD = b.comment("Database password. This file is plain text: keep it private.").define("password", "");
        STORAGE_TABLE_PREFIX = b.comment("Prefix for this mod's tables. Letters, digits and underscores only.").define("tablePrefix", "bsptp_");
        STORAGE_SSL = b.comment("Whether to use SSL for the database connection.").define("ssl", false);
        b.pop();

        b.push("redis");
        REDIS_ENABLED = b.comment("Whether to use Redis for live messages between servers (teleport requests, player lists, random teleports). Needs storage.mode = mysql.")
                .define("enabled", false);
        REDIS_HOST = b.define("host", "localhost");
        REDIS_PORT = b.defineInRange("port", 6379, 1, 65535);
        REDIS_PASSWORD = b.comment("Password of the Redis default user, or empty.").define("password", "");
        REDIS_SSL = b.define("ssl", false);
        REDIS_DB = b.comment("Redis database index.").defineInRange("database", 0, 0, 15);
        REDIS_CLUSTER = b.comment("Name shared by every server that should talk to each other. Change it to run two separate networks on one Redis.")
                .define("cluster", "main");
        b.pop();

        b.push("raid");
        RAID_HOME_DISTANCE = b.comment("While a player's totem is being stolen, no home, warp, /back or request may land them within this many blocks of it. Recall (a totem upgrade) is the way back in.")
                .defineInRange("homeDistance", 150, 0, 10_000);
        RAID_RANDOM_DISTANCE = b.comment("While a player's totem is being stolen, /rtp will not land them within this many blocks of it.")
                .defineInRange("randomDistance", 200, 0, 10_000);
        b.pop();

        b.push("sounds");
        SOUNDS = b.comment("Play sounds for the warm-up countdown, arrival, a cancelled teleport and a received request.").define("enabled", true);
        b.pop();

        b.push("spawn");
        GLOBAL_SPAWN_WARP = b.comment("Name of a warp that /spawn uses on every server, or empty to use each server's own /setspawn point.")
                .define("globalWarp", "");
        b.pop();

        b.push("homes");
        HOME_SLOTS = b.comment("Homes every player may set before totem upgrades add more.").defineInRange("slots", 3, 0, 100);
        b.pop();

        b.push("randomTeleport");
        RTP_ENABLED = b.comment("Whether /rtp works on this server.").define("enabled", true);
        RTP_MIN_RADIUS = b.comment("Nearest distance from the world spawn a random teleport may land.").defineInRange("minRadius", 500, 0, 1_000_000);
        RTP_MAX_RADIUS = b.comment("Farthest distance from the world spawn a random teleport may land.").defineInRange("maxRadius", 5000, 1, 1_000_000);
        RTP_TRIES = b.comment("Spots tried before giving up.").defineInRange("tries", 20, 1, 200);
        RTP_MIN_Y = b.comment("Lowest Y a random teleport may land at.").defineInRange("minY", -64, -2048, 2048);
        RTP_MAX_Y = b.comment("Highest Y a random teleport may land at. In worlds with a ceiling (the Nether) the search starts from here and goes down.")
                .defineInRange("maxY", 320, -2048, 2048);
        RTP_WORLDS = b.comment("Worlds where /rtp is allowed, as dimension ids. The first one is used for players arriving from other servers.")
                .defineListAllowEmpty("worlds", List.of("minecraft:overworld"), o -> o instanceof String);
        RTP_UNSAFE_BLOCKS = b.comment("Blocks a random teleport will not land on, as block ids.")
                .defineListAllowEmpty("unsafeBlocks", List.of("minecraft:lava", "minecraft:fire", "minecraft:soul_fire", "minecraft:magma_block",
                        "minecraft:cactus", "minecraft:campfire", "minecraft:soul_campfire", "minecraft:powder_snow", "minecraft:pointed_dripstone",
                        "minecraft:sweet_berry_bush", "minecraft:wither_rose", "minecraft:bedrock"), o -> o instanceof String);
        b.pop();

        b.push("back");
        BACK_RESTRICTED_WORLDS = b.comment("Worlds /back will not return a player to, as dimension ids. Leaving one of these does not save a /back position.")
                .defineListAllowEmpty("restrictedWorlds", List.of(), o -> o instanceof String);
        b.pop();

        b.push("requests");
        REQUEST_SECONDS = b.comment("Seconds a teleport request stays open.").defineInRange("expirySeconds", 60, 5, 600);
        b.pop();

        b.push("portals");
        PORTAL_MAX_SIZE = b.comment("Most blocks a portal frame may be across or tall.").defineInRange("maxSize", 24, 3, 64);
        PORTAL_MAX_OPENING = b.comment("Most cells a portal's opening may have.").defineInRange("maxOpening", 300, 1, 4096);
        PORTAL_TIME_ZONE = b.comment("Time zone for portals on a real-time clock, for example Europe/London. Empty means this machine's zone.").define("timeZone", "");
        b.pop();

        b.push("access");
        ACCESS_ENABLED = b.comment("The server access system (main server, rented and full access through Access Tokens). Off: no checks, no HUD, no board.").define("enabled", false);
        ACCESS_LOBBY = b.comment("The network's main spawn server, where a player without access is sent. Empty: the server of the global spawn warp, else the server they came from.").define("lobbyServer", "");
        ACCESS_RENT_HOURS = b.comment("Rent length in hours a token grants when its assignment gives none.").defineInRange("rentHours", 168, 1, 8760);
        b.pop();

        SPEC = b.build();
    }

    private TeleportConfig() {
    }

    /** Reads a value, or the fallback when the config is not loaded yet (dedicated server startup, tests). */
    public static <T> T getOr(ForgeConfigSpec.ConfigValue<T> value, T fallback) {
        try {
            return value.get();
        } catch (IllegalStateException | NullPointerException e) {
            return fallback;
        }
    }

    public static String serverName() {
        return getOr(SERVER_NAME, "server-1");
    }

    /** Re-reads the config file from disk ({@code /bsptp reload}). Forge also does this by itself when the file changes. */
    public static void reload(net.minecraft.server.MinecraftServer server) {
        java.nio.file.Path path = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("serverconfig").resolve("bsp_teleport-server.toml");
        com.electronwill.nightconfig.core.file.CommentedFileConfig file = com.electronwill.nightconfig.core.file.CommentedFileConfig.builder(path)
                .sync().autosave().writingMode(com.electronwill.nightconfig.core.io.WritingMode.REPLACE).build();
        file.load();
        SPEC.setConfig(file);
    }

    public static boolean mysql() {
        return "mysql".equalsIgnoreCase(getOr(STORAGE_MODE, "local"));
    }

    public static boolean redis() {
        return mysql() && getOr(REDIS_ENABLED, false);
    }
}
