package com.mrgregles.bsp_teleport.portal;

import net.minecraft.util.StringRepresentable;

/** The 24 field colours (D58): the 16 dyes and eight of the mod's own. The field texture is grey; the tint is applied on the client. */
public enum PortalColour implements StringRepresentable {
    WHITE(0xF0F0F0), LIGHT_GRAY(0x9D9D97), GRAY(0x474F52), BLACK(0x1D1D21),
    RED(0xB02E26), ORANGE(0xF9801D), YELLOW(0xFED83D), LIME(0x80C71F),
    GREEN(0x5E7C16), CYAN(0x169C9C), LIGHT_BLUE(0x3AB3DA), BLUE(0x3C44AA),
    PURPLE(0x8932B8), MAGENTA(0xC74EBD), PINK(0xF38BAA), BROWN(0x835432),
    TURQUOISE(0x19D3B0), VIOLET(0xB58CFF), GOLD(0xFFD23A), ROSE(0xFF6B8A),
    MINT(0x8FE0A4), AMBER(0xFF9F1C), ICE(0xBFEFFF), EMBER(0xFF5A2A);

    public static final PortalColour DEFAULT = TURQUOISE;
    private final int tint;

    PortalColour(int tint) {
        this.tint = tint;
    }

    /** 0xRRGGBB. */
    public int tint() {
        return tint;
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static PortalColour byName(String name) {
        for (PortalColour c : values()) {
            if (c.getSerializedName().equalsIgnoreCase(name)) {
                return c;
            }
        }
        return DEFAULT;
    }

    public PortalColour next(int step) {
        PortalColour[] all = values();
        return all[Math.floorMod(ordinal() + step, all.length)];
    }
}
