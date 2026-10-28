package com.mrgregles.bsp_teleport.client;

import com.mrgregles.bsp_teleport.access.OpenHours;
import net.minecraft.client.gui.GuiGraphics;

import javax.annotation.Nullable;

/** The day bar of D150: 24 hours left to right, the open hours lit, a gold mark at the server's time now. */
final class HoursBar {
    /** The lit hours: turquoise at a little over half strength, so the gold mark stands out on it. */
    private static final int LIT = 0x9919D3B0;

    private HoursBar() {
    }

    /** @param now the minute of the day to mark, or -1 for no mark */
    static void draw(GuiGraphics g, int x, int y, int w, int h, @Nullable OpenHours hours, int now) {
        g.fill(x, y, x + w, y + h, BandClient.DIM);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, BandClient.SLOT);
        int inner = w - 2;
        if (hours != null) {
            for (OpenHours.Window win : hours.windows()) {
                int open = win.open(), close = win.close();
                if (open < close) {
                    span(g, x + 1, y + 1, inner, h - 2, open, close);
                } else {
                    // Across midnight: to the end of the bar, then from its start.
                    span(g, x + 1, y + 1, inner, h - 2, open, OpenHours.DAY);
                    span(g, x + 1, y + 1, inner, h - 2, 0, close);
                }
            }
        }
        if (now >= 0) {
            int nx = x + 1 + now * inner / OpenHours.DAY;
            g.fill(nx - 1, y - 2, nx + 1, y + h + 2, BandClient.GOLD);
        }
    }

    private static void span(GuiGraphics g, int x, int y, int w, int h, int from, int to) {
        int a = x + from * w / OpenHours.DAY, b = x + to * w / OpenHours.DAY;
        g.fill(a, y, Math.max(a + 1, b), y + h, LIT);
    }
}
