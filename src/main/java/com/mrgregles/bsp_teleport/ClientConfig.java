package com.mrgregles.bsp_teleport;

import net.minecraftforge.common.ForgeConfigSpec;

/** Client config: {@code config/bsp_teleport-client.toml}. Only the screens read it. */
public final class ClientConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.DoubleValue SCREEN_SCALE;
    public static final ForgeConfigSpec.ConfigValue<String> ACCESS_HUD;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("ui");
        SCREEN_SCALE = b.comment("How much bigger the band and admin screens are drawn than vanilla's GUI scale. 1 is vanilla size; 1.5 is half again as big.",
                        "The screens shrink by themselves when the window is too small for this.")
                .defineInRange("screenScale", 1.5, 0.5, 4.0);
        ACCESS_HUD = b.comment("How the access icons are drawn above the hotbar: row, strip or column (D89).").define("accessHud", "row");
        b.pop();
        SPEC = b.build();
    }

    private ClientConfig() {
    }

    private static final String[] HUDS = {"row", "strip", "column"};

    /** row, strip or column. */
    public static String accessHud() {
        String v = TeleportConfig.getOr(ACCESS_HUD, "row");
        for (String h : HUDS) {
            if (h.equalsIgnoreCase(v)) {
                return h;
            }
        }
        return "row";
    }

    /** Steps to the next layout and saves the file. */
    public static String cycleAccessHud() {
        String cur = accessHud();
        String next = HUDS[(java.util.Arrays.asList(HUDS).indexOf(cur) + 1) % HUDS.length];
        try {
            ACCESS_HUD.set(next);
            SPEC.save();
        } catch (RuntimeException ignored) {
            // the config is not loaded (no client); nothing to save
        }
        return next;
    }

    /** The scale a panel of {@code panelW x panelH} is drawn at in a window of {@code width x height} GUI units. */
    public static float screenScale(int width, int height, int panelW, int panelH) {
        double s = TeleportConfig.getOr(SCREEN_SCALE, 1.5);
        s = Math.min(s, Math.min((width - 8.0) / (panelW + 2), (height - 8.0) / (panelH + 2)));
        return (float) Math.max(0.5, s);
    }
}
