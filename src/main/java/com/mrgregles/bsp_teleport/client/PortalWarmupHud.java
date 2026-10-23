package com.mrgregles.bsp_teleport.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;

/**
 * A portal warm-up's box at the top of the screen (D126, D139): the portal's name, "Going in N s. Crouch to stay." and a
 * bar that empties. The server sends the start and once a second (packet 13); between those the client counts its own
 * ticks so the bar runs smoothly.
 */
public final class PortalWarmupHud {
    private static final int W = 160, H = 30;
    private static String portal = "";
    private static int totalTicks, ticksLeft;
    private static long shownAt;

    private PortalWarmupHud() {
    }

    /** Packet 13: show or update the box; an empty name hides it. */
    public static void show(String name, int total, int left, boolean isHeld) {
        Minecraft mc = Minecraft.getInstance();
        portal = name;
        totalTicks = Math.max(1, total);
        ticksLeft = left;
        shownAt = mc.level == null ? 0 : mc.level.getGameTime();
    }

    public static void clear() {
        portal = "";
    }

    public static void onOverlay(RenderGuiOverlayEvent.Post event) {
        if (portal.isEmpty() || !event.getOverlay().id().equals(VanillaGuiOverlay.HOTBAR.id())) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) {
            return;
        }
        int left = (int) Math.max(0, ticksLeft - (mc.level.getGameTime() - shownAt));
        GuiGraphics g = event.getGuiGraphics();
        int x = (event.getWindow().getGuiScaledWidth() - W) / 2, y = 6;
        g.fill(x - 1, y - 1, x + W + 1, y + H + 1, BandClient.TQ);
        g.fill(x, y, x + W, y + H, BandClient.BG);
        small(g, mc, portal.toUpperCase(), x + 6, y + 4, BandClient.TQ);
        int seconds = (left + 19) / 20;
        String line = Component.translatable("screen.bsp_teleport.portal.warmup_going", seconds).getString();
        small(g, mc, line, x + 6, y + 13, BandClient.MUTED);
        int barX = x + 6, barY = y + H - 7, barW = W - 12;
        g.fill(barX, barY, barX + barW, barY + 3, BandClient.SLOT);
        g.fill(barX, barY, barX + barW * left / totalTicks, barY + 3, BandClient.TQ);
    }

    private static void small(GuiGraphics g, Minecraft mc, String text, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().scale(0.75f, 0.75f, 1f);
        g.drawString(mc.font, text, (int) (x / 0.75f), (int) (y / 0.75f), colour, false);
        g.pose().popPose();
    }
}
