package com.mrgregles.bsp_teleport.client;

import com.mrgregles.bsp_teleport.ClientConfig;
import com.mrgregles.bsp_teleport.access.AccessAction;
import com.mrgregles.bsp_teleport.access.AccessGrant;
import com.mrgregles.bsp_teleport.access.AccessPopup;
import com.mrgregles.bsp_teleport.access.TokenAssignment;
import com.mrgregles.bsp_teleport.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The two access popups in the Banner layout (D81, D82, D89): a wide band across the top of the screen. DENIED shows
 * what the player needs and the token stacks that sell it; CHOOSE lists the restricted servers for the one free
 * main-server choice, with a confirmation before it is sent.
 */
public class AccessScreen extends Screen {
    private static final int W = 320;
    private static final int TQ = BandClient.TQ, BG = BandClient.BG, SLOT = BandClient.SLOT, DIM = BandClient.DIM, MUTED = BandClient.MUTED,
            TEXT = BandClient.TEXT, VIO = BandClient.VIO, GOLD = BandClient.GOLD, BAD = BandClient.BAD, OK = BandClient.OK;

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private AccessPopup data;
    private int left, top;
    private float scale = 1f;
    private final List<Hit> hits = new ArrayList<>();
    @Nullable
    private String confirming;

    public AccessScreen(AccessPopup data) {
        super(Component.translatable("screen.bsp_teleport.access.title"));
        this.data = data;
    }

    public static void show(AccessPopup data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof AccessScreen screen) {
            screen.data = data;
            screen.confirming = null;
        } else {
            mc.setScreen(new AccessScreen(data));
        }
    }

    private int height() {
        return data.type() == AccessPopup.Type.DENIED ? 96 : 70 + Math.min(6, data.servers().size()) * 18 + (confirming != null ? 22 : 0);
    }

    @Override
    protected void init() {
        scale = ClientConfig.screenScale(width, height, W, 160);
        left = ((int) (width / scale) - W) / 2;
        top = 16;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1f);
        hits.clear();
        int h = height();
        int border = data.type() == AccessPopup.Type.DENIED ? BAD : GOLD;
        g.fill(left - 1, top - 1, left + W + 1, top + h + 1, border);
        g.fill(left, top, left + W, top + h, BG);
        if (data.type() == AccessPopup.Type.DENIED) {
            drawDenied(g, border);
        } else {
            drawChoose(g);
        }
        super.render(g, (int) (mouseX / scale), (int) (mouseY / scale), partial);
        g.pose().popPose();
    }

    private ItemStack tokenStack(int token, AccessGrant.Tier tier, int hours) {
        if (token < 1 || token > TokenAssignment.COUNT) {
            return ItemStack.EMPTY;
        }
        var item = (com.mrgregles.bsp_teleport.item.AccessTokenItem) ModItems.ACCESS_TOKENS[token - 1].get();
        return item.stack(tier, hours);
    }

    private void drawDenied(GuiGraphics g, int border) {
        g.drawString(font, Component.translatable("screen.bsp_teleport.access.denied_title", data.target()).getString().toUpperCase(), left + 10, top + 8, border, false);
        small(g, (data.hint().isEmpty() ? Component.translatable("screen.bsp_teleport.access.denied_help").getString() : Component.translatable("screen.bsp_teleport.access.denied_help_hint", data.hint()).getString()),
                left + 10, top + 19, MUTED);
        int y = top + 32;
        if (data.token() > 0) {
            String name = "Access Token " + TokenAssignment.roman(data.token());
            itemRow(g, left + 10, y, (W - 26) / 2, tokenStack(data.token(), AccessGrant.Tier.RENT, data.rentHours()), name,
                    Component.translatable("screen.bsp_teleport.access.rent_stack", data.target(), data.rentHours()).getString());
            itemRow(g, left + 10 + (W - 26) / 2 + 6, y, (W - 26) / 2, tokenStack(data.token(), AccessGrant.Tier.FULL, 0), name,
                    Component.translatable("screen.bsp_teleport.access.full_stack", data.target()).getString());
        } else {
            g.fill(left + 10, y, left + W - 10, y + 30, DIM);
            g.fill(left + 11, y + 1, left + W - 11, y + 29, SLOT);
            small(g, Component.translatable("screen.bsp_teleport.access.no_token", data.target()).getString(), left + 16, y + 11, MUTED);
        }
        int bx = left + W - 10;
        bx = button(g, bx, top + 96 - 20, Component.translatable("screen.bsp_teleport.access.close").getString(), DIM, MUTED, this::onClose) - 4;
        if (data.noMain()) {
            button(g, bx, top + 96 - 20, Component.translatable("screen.bsp_teleport.access.choose_main").getString(), GOLD, GOLD, () -> ClientNet.sendAccess(AccessAction.CHOOSE_OPEN, ""));
        }
    }

    private void drawChoose(GuiGraphics g) {
        g.drawString(font, Component.translatable("screen.bsp_teleport.access.choose_title").getString().toUpperCase(), left + 10, top + 8, GOLD, false);
        small(g, Component.translatable("screen.bsp_teleport.access.choose_help").getString(), left + 10, top + 19, MUTED);
        int y = top + 32;
        int shown = 0;
        for (AccessPopup.ServerChoice s : data.servers()) {
            if (shown++ >= 6) {
                break;
            }
            boolean sel = s.server().equals(confirming);
            g.fill(left + 10, y, left + W - 10, y + 16, sel ? GOLD : DIM);
            g.fill(left + 11, y + 1, left + W - 11, y + 15, SLOT);
            if (s.token() > 0) {
                g.pose().pushPose();
                g.pose().translate(left + 13, y + 2, 0);
                g.pose().scale(0.75f, 0.75f, 1f);
                g.renderItem(tokenStack(s.token(), AccessGrant.Tier.FULL, 0), 0, 0);
                g.pose().popPose();
            }
            g.drawString(font, s.server(), left + 28, y + 4, sel ? GOLD : TEXT, false);
            small(g, Component.translatable("screen.bsp_teleport.access.counts", s.home(), s.full(), s.rent()).getString(), left + 28 + font.width(s.server()) + 8, y + 5, GOLD);
            String name = s.server();
            button(g, left + W - 14, y + 2, Component.translatable("screen.bsp_teleport.access.choose").getString(), GOLD, GOLD, () -> confirming = name);
            y += 18;
        }
        if (data.servers().isEmpty()) {
            small(g, Component.translatable("screen.bsp_teleport.access.no_servers").getString(), left + 12, y + 4, MUTED);
            y += 18;
        }
        if (confirming != null) {
            g.fill(left + 10, y, left + W - 10, y + 18, GOLD);
            g.fill(left + 11, y + 1, left + W - 11, y + 17, SLOT);
            small(g, Component.translatable("screen.bsp_teleport.access.confirm", confirming).getString(), left + 14, y + 2, TEXT);
            small(g, Component.translatable("screen.bsp_teleport.access.confirm_help").getString(), left + 14, y + 10, MUTED);
            int bx = left + W - 14;
            bx = button(g, bx, y + 3, Component.translatable("screen.bsp_teleport.admin.no").getString(), DIM, MUTED, () -> confirming = null) - 4;
            String name = confirming;
            button(g, bx, y + 3, Component.translatable("screen.bsp_teleport.admin.yes").getString(), OK, OK, () -> {
                ClientNet.sendAccess(AccessAction.CHOOSE, name);
                onClose();
            });
            y += 22;
        }
        button(g, left + W - 10, y + 2, Component.translatable("screen.bsp_teleport.access.close").getString(), DIM, MUTED, this::onClose);
    }

    private void itemRow(GuiGraphics g, int x, int y, int w, ItemStack stack, String name, String sub) {
        g.fill(x, y, x + w, y + 30, DIM);
        g.fill(x + 1, y + 1, x + w - 1, y + 29, SLOT);
        g.renderItem(stack, x + 6, y + 7);
        g.drawString(font, name, x + 28, y + 6, TEXT, false);
        small(g, clip(sub, w - 34), x + 28, y + 18, GOLD);
    }

    private int button(GuiGraphics g, int rightX, int y, String label, int border, int colour, Runnable action) {
        int w = (int) (font.width(label.toUpperCase()) * 0.75f) + 8;
        int x = rightX - w;
        g.fill(x, y, x + w, y + 12, border);
        g.fill(x + 1, y + 1, x + w - 1, y + 11, SLOT);
        small(g, label.toUpperCase(), x + 4, y + 3, colour);
        hits.add(new Hit(x, y, w, 12, action));
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
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (minecraft.options.keyInventory.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}
