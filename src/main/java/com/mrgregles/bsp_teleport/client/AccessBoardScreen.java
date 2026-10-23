package com.mrgregles.bsp_teleport.client;

import com.mrgregles.bsp_teleport.ClientConfig;
import com.mrgregles.bsp_teleport.block.AccessBoardBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** The Access Board's settings (admins): the style (D89: Bars, Tiles or Columns per board), the size and the title. */
public class AccessBoardScreen extends Screen {
    private static final int W = 240, H = 110;
    private static final int TQ = BandClient.TQ, BG = BandClient.BG, SLOT = BandClient.SLOT, DIM = BandClient.DIM, MUTED = BandClient.MUTED, TEXT = BandClient.TEXT,
            VIO = BandClient.VIO, GOLD = BandClient.GOLD;

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final BlockPos pos;
    private int style, size;
    private String title;
    private int left, top;
    private float scale = 1f;
    private final List<Hit> hits = new ArrayList<>();
    private EditBox titleBox;

    public AccessBoardScreen(BlockPos pos, int style, int size, String title) {
        super(Component.translatable("screen.bsp_teleport.board.title"));
        this.pos = pos;
        this.style = style;
        this.size = size;
        this.title = title;
    }

    public static void open(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getBlockEntity(pos) instanceof AccessBoardBlockEntity board) {
            mc.setScreen(new AccessBoardScreen(pos, board.style(), board.size(), board.title()));
        }
    }

    @Override
    protected void init() {
        scale = ClientConfig.screenScale(width, height, W, H);
        left = ((int) (width / scale) - W) / 2;
        top = ((int) (height / scale) - H) / 2;
        titleBox = new EditBox(font, left + 10, top + 62, 150, 14, Component.literal("title"));
        titleBox.setMaxLength(AccessBoardBlockEntity.TITLE_MAX);
        titleBox.setValue(title);
        addRenderableWidget(titleBox);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void apply() {
        ClientNet.sendBoard(pos, style, size, titleBox.getValue().trim());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1f);
        hits.clear();
        g.fill(left - 1, top - 1, left + W + 1, top + H + 1, VIO);
        g.fill(left, top, left + W, top + H, BG);
        g.drawString(font, this.title().getString().toUpperCase(), left + 10, top + 8, VIO, false);
        small(g, clip(Component.translatable("screen.bsp_teleport.board.help").getString(), W - 20), left + 10, top + 19, MUTED);
        int y = top + 32;
        small(g, Component.translatable("screen.bsp_teleport.board.style").getString(), left + 10, y + 3, TEXT);
        int x = left + 60;
        String[] styles = {"bars", "tiles", "columns"};
        for (int i = 0; i < styles.length; i++) {
            String label = Component.translatable("screen.bsp_teleport.board.style." + styles[i]).getString();
            int w = (int) (font.width(label.toUpperCase()) * 0.75f) + 8;
            boolean on = style == i;
            g.fill(x, y, x + w, y + 12, on ? VIO : DIM);
            g.fill(x + 1, y + 1, x + w - 1, y + 11, SLOT);
            small(g, label.toUpperCase(), x + 4, y + 3, on ? VIO : MUTED);
            int pick = i;
            hits.add(new Hit(x, y, w, 12, () -> {
                style = pick;
                apply();
            }));
            x += w + 3;
        }
        y += 16;
        small(g, Component.translatable("screen.bsp_teleport.board.size").getString(), left + 10, y + 3, TEXT);
        int bx = left + 60;
        bx = button(g, bx + 12, y, "-", DIM, TQ, () -> {
            size = Math.max(60, size - 10);
            apply();
        }) + 14;
        small(g, size + "%", bx + 2, y + 3, GOLD);
        button(g, bx + 40, y, "+", DIM, TQ, () -> {
            size = Math.min(160, size + 10);
            apply();
        });
        titleBox.setY(top + 62);
        int done = button(g, left + W - 10, top + H - 22, Component.translatable("screen.bsp_teleport.board.done").getString(), TQ, TQ, () -> {
            apply();
            onClose();
        });
        button(g, done - 4, top + H - 22, Component.translatable("screen.bsp_teleport.portal.set").getString(), GOLD, GOLD, this::apply);
        super.render(g, (int) (mouseX / scale), (int) (mouseY / scale), partial);
        g.pose().popPose();
    }

    private Component title() {
        return super.getTitle();
    }

    /** Draws a button whose right edge is at {@code rightX}; returns its left edge. */
    private int button(GuiGraphics g, int rightX, int y, String label, int border, int colour, Runnable action) {
        int w = (int) (font.width(label.toUpperCase()) * 0.75f) + 8;
        int x = rightX - w;
        g.fill(x, y, x + w, y + 12, border);
        g.fill(x + 1, y + 1, x + w - 1, y + 11, SLOT);
        small(g, label.toUpperCase(), x + 4, y + 3, colour);
        hits.add(new Hit(x, y, w, 12, action));
        return x;
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

    private void small(GuiGraphics g, String text, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().scale(0.75f, 0.75f, 1f);
        g.drawString(font, text, (int) (x / 0.75f), (int) (y / 0.75f), colour, false);
        g.pose().popPose();
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
        if (!titleBox.isFocused() && minecraft.options.keyInventory.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}
