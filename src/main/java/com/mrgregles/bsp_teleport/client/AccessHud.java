package com.mrgregles.bsp_teleport.client;

import com.mrgregles.bsp_teleport.ClientConfig;
import com.mrgregles.bsp_teleport.access.AccessGrant;
import com.mrgregles.bsp_teleport.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;

import java.util.List;

/**
 * The access icons above the hotbar (D79, D89): one per server the player may enter, the server's token as the icon,
 * a gold frame for the main server, turquoise for full access, violet with a countdown for rented. Three layouts the
 * player picks in the client config or from the band: Row, Strip or Column. Drawn right of the hotbar's centre, since
 * BSP-Core's Recall card uses the left.
 */
public final class AccessHud {
    private static final int GOLD = BandClient.GOLD, TQ = BandClient.TQ, VIO = BandClient.VIO, TEXT = BandClient.TEXT, STRIP_BG = 0xA0000000;

    private AccessHud() {
    }

    public static void onOverlay(RenderGuiOverlayEvent.Post event) {
        if (!event.getOverlay().id().equals(VanillaGuiOverlay.HOTBAR.id())) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.player.isSpectator() || !ClientAccess.enabled()) {
            return;
        }
        List<ClientAccess.Entry> entries = ClientAccess.hudEntries();
        if (entries.isEmpty()) {
            return;
        }
        GuiGraphics g = event.getGuiGraphics();
        int w = event.getWindow().getGuiScaledWidth(), h = event.getWindow().getGuiScaledHeight();
        switch (ClientConfig.accessHud()) {
            case "strip" -> strip(g, mc, entries, w, h);
            case "column" -> column(g, mc, entries, w, h);
            default -> row(g, mc, entries, w, h);
        }
    }

    private static int frame(ClientAccess.Entry e) {
        return e.tier() == AccessGrant.Tier.MAIN ? GOLD : e.tier() == AccessGrant.Tier.FULL ? TQ : VIO;
    }

    private static ItemStack icon(ClientAccess.Entry e) {
        int token = ClientAccess.tokenFor(e.kind(), e.target());
        return token == 0 ? new ItemStack(ModItems.TELEPORT_BAND.get()) : new ItemStack(ModItems.ACCESS_TOKENS[token - 1].get());
    }

    private static String shortName(String server) {
        String s = server.startsWith("BSP-") ? server.substring(4) : server;
        return s.length() > 9 ? s.substring(0, 9) : s;
    }

    private static void row(GuiGraphics g, Minecraft mc, List<ClientAccess.Entry> entries, int w, int h) {
        int x = w / 2 + 22, y = h - 22 - 34;
        for (ClientAccess.Entry e : entries) {
            g.fill(x - 1, y - 1, x + 17, y + 17, frame(e));
            g.fill(x, y, x + 16, y + 16, 0xC0101418);
            g.renderItem(icon(e), x, y);
            tiny(g, mc, shortName(e.target()), x, y + 18, TEXT);
            if (e.tier() == AccessGrant.Tier.RENT) {
                tiny(g, mc, com.mrgregles.bsp_teleport.access.AccessGrant.describeLeft(e.remainingMs()), x, y + 24, 0xFFD9C9FF);
            }
            x += 30;
        }
    }

    private static void strip(GuiGraphics g, Minecraft mc, List<ClientAccess.Entry> entries, int w, int h) {
        int x = w / 2 + 12, y = h - 22 - 26;
        int width = 6;
        for (ClientAccess.Entry e : entries) {
            width += 16 + (int) (mc.font.width(label(e)) * 0.5f) + 8;
        }
        g.fill(x - 3, y - 2, x + width, y + 14, STRIP_BG);
        for (ClientAccess.Entry e : entries) {
            g.fill(x - 1, y - 1, x + 13, y + 13, frame(e));
            g.pose().pushPose();
            g.pose().translate(x, y, 0);
            g.pose().scale(0.75f, 0.75f, 1f);
            g.renderItem(icon(e), 0, 0);
            g.pose().popPose();
            tiny(g, mc, label(e), x + 16, y + 4, e.tier() == AccessGrant.Tier.RENT ? 0xFFD9C9FF : TEXT);
            x += 16 + (int) (mc.font.width(label(e)) * 0.5f) + 8;
        }
    }

    private static void column(GuiGraphics g, Minecraft mc, List<ClientAccess.Entry> entries, int w, int h) {
        int x = w - 8 - 16, y = h - 48;
        for (ClientAccess.Entry e : entries) {
            g.fill(x - 1, y - 1, x + 17, y + 17, frame(e));
            g.fill(x, y, x + 16, y + 16, 0xC0101418);
            g.renderItem(icon(e), x, y);
            String text = label(e);
            tiny(g, mc, text, x - 4 - (int) (mc.font.width(text) * 0.5f), y + 5, e.tier() == AccessGrant.Tier.RENT ? 0xFFD9C9FF : TEXT);
            y -= 20;
        }
    }

    private static String label(ClientAccess.Entry e) {
        return e.tier() == AccessGrant.Tier.RENT ? shortName(e.target()) + " " + AccessGrant.describeLeft(e.remainingMs()) : shortName(e.target());
    }

    private static void tiny(GuiGraphics g, Minecraft mc, String text, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().scale(0.5f, 0.5f, 1f);
        g.drawString(mc.font, text, (int) (x / 0.5f), (int) (y / 0.5f), colour, true);
        g.pose().popPose();
    }
}
