package com.mrgregles.bsp_teleport;

import net.minecraftforge.common.ForgeConfigSpec;

/** Server config: {@code <world>/serverconfig/bsp_teleport-server.toml}. */
public final class TeleportConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> SERVER_NAME;
    public static final ForgeConfigSpec.IntValue LANDING_SECONDS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("network");
        SERVER_NAME = b.comment("This server's name as the Velocity proxy knows it (velocity.toml [servers]). Used to tell cross-server teleports apart.")
                .define("serverName", "server-1");
        LANDING_SECONDS = b.comment("How long a cross-server landing waits for its player to arrive before it is forgotten.")
                .defineInRange("landingSeconds", 120, 10, 3600);
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
}
