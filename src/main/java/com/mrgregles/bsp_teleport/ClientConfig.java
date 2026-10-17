package com.mrgregles.bsp_teleport;

import net.minecraftforge.common.ForgeConfigSpec;

/** Client config: {@code config/bsp_teleport-client.toml}. Only the screens read it. */
public final class ClientConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.DoubleValue SCREEN_SCALE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("ui");
        SCREEN_SCALE = b.comment("How much bigger the band and admin screens are drawn than vanilla's GUI scale. 1 is vanilla size; 1.5 is half again as big.",
                        "The screens shrink by themselves when the window is too small for this.")
                .defineInRange("screenScale", 1.5, 0.5, 4.0);
        b.pop();
        SPEC = b.build();
    }

    private ClientConfig() {
    }

    /** The scale a panel of {@code panelW x panelH} is drawn at in a window of {@code width x height} GUI units. */
    public static float screenScale(int width, int height, int panelW, int panelH) {
        double s = TeleportConfig.getOr(SCREEN_SCALE, 1.5);
        s = Math.min(s, Math.min((width - 8.0) / (panelW + 2), (height - 8.0) / (panelH + 2)));
        return (float) Math.max(0.5, s);
    }
}
