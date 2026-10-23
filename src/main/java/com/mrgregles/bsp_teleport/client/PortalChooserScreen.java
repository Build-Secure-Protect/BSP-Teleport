package com.mrgregles.bsp_teleport.client;

import com.mrgregles.bsp_teleport.ClientConfig;
import com.mrgregles.bsp_teleport.portal.PortalChoice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The destination chooser (D97, D107 B Centred card): a card in the middle of the screen titled with the portal's
 * name, one row per destination with GO, down rows greyed with the reason, denied rows red with the reason, and
 * Cancel. Every key is swallowed except Esc, which cancels (D98); the server holds the player meanwhile. The server
 * closes it by sending an empty option list.
 */
public class PortalChooserScreen extends Screen {
    private static final int W = 220;
    private static final int TQ = BandClient.TQ, BG = BandClient.BG, SLOT = BandClient.SLOT, DIM = BandClient.DIM, MUTED = BandClient.MUTED,
            TEXT = BandClient.TEXT, VIO = BandClient.VIO, GOLD = BandClient.GOLD, BAD = BandClient.BAD;

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private PortalChoice choice;
    private final List<Hit> hits = new ArrayList<>();
    private int left, top, h;
    private float scale = 1f;
    private boolean answered;

    private PortalChooserScreen(PortalChoice choice) {
        super(Component.translatable("screen.bsp_teleport.portal.title"));
        this.choice = choice;
    }

    /** Opens, updates or (for an empty list) closes the chooser. */
    public static void show(PortalChoice choice) {
        Minecraft mc = Minecraft.getInstance();
        if (choice.isClose()) {
            if (mc.screen instanceof PortalChooserScreen screen) {
                screen.answered = true;
                mc.setScreen(null);
            }
            return;
        }
        if (mc.screen instanceof PortalChooserScreen screen) {
            screen.choice = choice;
            screen.init(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        } else {
            mc.setScreen(new PortalChooserScreen(choice));
        }
    }

    @Override
    protected void init() {
        h = 44 + choice.options().size() * 20 + 22;
        scale = ClientConfig.screenScale(width, height, W, h);
        left = ((int) (width / scale) - W) / 2;
        top = ((int) (height / scale) - h) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1f);
        hits.clear();
        g.fill(left - 1, top - 1, left + W + 1, top + h + 1, VIO);
        g.fill(left, top, left + W, top + h, BG);
        g.drawString(font, choice.portal().getString().toUpperCase(), left + 10, top + 8, VIO, false);
        small(g, Component.translatable("screen.bsp_teleport.choose.help").getString(), left + 10, top + 19, MUTED);
        int y = top + 32;
        // D126: the Back entry is one wide key across the top, the other destinations under it.
        for (PortalChoice.Option o : choice.options()) {
            if (o.back()) {
                y = drawBack(g, o, y);
            }
        }
        for (PortalChoice.Option o : choice.options()) {
            if (o.back()) {
                continue;
            }
            boolean ok = o.state() == PortalChoice.State.OK, denied = o.state() == PortalChoice.State.DENIED;
            int border = ok ? DIM : denied ? BAD : DIM;
            g.fill(left + 10, y, left + W - 10, y + 18, border);
            g.fill(left + 11, y + 1, left + W - 11, y + 17, SLOT);
            small(g, clip(o.label().getString(), W - 80), left + 14, y + 3, ok ? TEXT : MUTED);
            small(g, clip(o.detail().getString(), W - 80), left + 14, y + 10, ok ? MUTED : denied ? BAD : DIM);
            String key = Component.translatable(ok ? "screen.bsp_teleport.choose.go" : denied ? "screen.bsp_teleport.choose.no_access" : "screen.bsp_teleport.choose.down").getString();
            int colour = ok ? TQ : denied ? BAD : DIM;
            button(g, left + W - 14, y + 3, key, colour, ok ? TQ : MUTED, ok ? () -> answer(o.index()) : null);
            y += 20;
        }
        button(g, left + W - 14, y + 4, Component.translatable("screen.bsp_teleport.choose.cancel").getString(), GOLD, GOLD, () -> answer(-1));
        g.pose().popPose();
    }

    /** The Back entry as a wide turquoise key (D126), greyed with the reason when it cannot be used. */
    private int drawBack(GuiGraphics g, PortalChoice.Option o, int y) {
        boolean ok = o.state() == PortalChoice.State.OK, denied = o.state() == PortalChoice.State.DENIED;
        int x1 = left + 10, x2 = left + W - 10;
        g.fill(x1, y, x2, y + 18, ok ? TQ : denied ? BAD : DIM);
        g.fill(x1 + 1, y + 1, x2 - 1, y + 17, SLOT);
        String label = clip(o.label().getString().toUpperCase(), W - 30);
        String detail = clip(o.detail().getString(), W - 30);
        int mid = (x1 + x2) / 2;
        small(g, label, mid - (int) (font.width(label) * 0.75f) / 2, y + 3, ok ? TQ : MUTED);
        small(g, detail, mid - (int) (font.width(detail) * 0.75f) / 2, y + 10, ok ? MUTED : denied ? BAD : DIM);
        if (ok) {
            hits.add(new Hit(x1, y, x2 - x1, 18, () -> answer(o.index())));
        }
        return y + 20;
    }

    private void answer(int index) {
        if (answered) {
            return;
        }
        answered = index < 0;
        ClientNet.sendPortalChoice(index);
        if (index < 0) {
            Minecraft.getInstance().setScreen(null);
        }
    }

    @Override
    public void onClose() {
        // Closed any other way (a mod, a disconnect): the server must not keep the hold.
        if (!answered) {
            answered = true;
            ClientNet.sendPortalChoice(-1);
        }
        super.onClose();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        mx /= scale;
        my /= scale;
        if (button == 0) {
            for (Hit hit : new ArrayList<>(hits)) {
                if (hit.contains(mx, my)) {
                    hit.action().run();
                    return true;
                }
            }
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            answer(-1);
        }
        // Every other key is swallowed (D98): no inventory, no movement, no commands while choosing.
        return true;
    }

    @Override
    public boolean charTyped(char c, int mods) {
        return true;
    }

    private int button(GuiGraphics g, int rightX, int y, String label, int border, int colour, Runnable action) {
        int w = (int) (font.width(label.toUpperCase()) * 0.75f) + 8;
        int x = rightX - w;
        g.fill(x, y, x + w, y + 12, border);
        g.fill(x + 1, y + 1, x + w - 1, y + 11, SLOT);
        small(g, label.toUpperCase(), x + 4, y + 3, colour);
        if (action != null) {
            hits.add(new Hit(x, y, w, 12, action));
        }
        return x;
    }

    private void small(GuiGraphics g, String text, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().scale(0.75f, 0.75f, 1f);
        g.drawString(font, text, (int) (x / 0.75f), (int) (y / 0.75f), colour, false);
        g.pose().popPose();
    }

    private String clip(String text, int maxWidth) {
        if (font.width(text) * 0.75f <= maxWidth) {
            return text;
        }
        while (text.length() > 3 && font.width(text + "...") * 0.75f > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "...";
    }
}
