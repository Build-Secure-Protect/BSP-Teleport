package com.mrgregles.bsp_teleport.client;

import com.mrgregles.bsp_teleport.band.BandAction;
import com.mrgregles.bsp_teleport.band.BandData;
import com.mrgregles.bsp_teleport.ClientConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The Teleport Band's screen, in the Holo HUD style: a tabbed list of homes, warps and players, a MORE tab for
 * spawn, back, random and the request switch, and a footer with the cooldown and the home slots.
 *
 * <p>The server decides everything: the screen only shows {@link BandData} and sends {@link BandAction}s. Starting a
 * teleport closes the screen; the warm-up counts down on the action bar.
 */
public class BandScreen extends Screen {
    private static final int W = 248, H = 196, TAB_W = 44, ROW_H = 20, LIST_TOP = 30, FOOT_H = 22;
    private static final int TQ = BandClient.TQ, BG = BandClient.BG, SLOT = BandClient.SLOT, DIM = BandClient.DIM, MUTED = BandClient.MUTED,
            TEXT = BandClient.TEXT, VIO = BandClient.VIO, GOLD = BandClient.GOLD, BAD = BandClient.BAD, OK = BandClient.OK;

    private enum Tab { HOMES, WARPS, PLAYERS, MORE, ADMIN }

    /** A clickable box drawn this frame. */
    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private BandData data;
    private Tab tab = Tab.HOMES;
    private int scroll;
    private int left, top;
    /** Everything is drawn this many times bigger than vanilla's GUI scale; mouse positions are divided by it. */
    private float scale = 1f;
    private final List<Hit> hits = new ArrayList<>();
    private EditBox nameBox;

    public BandScreen(BandData data) {
        super(Component.translatable("screen.bsp_teleport.band.title"));
        this.data = data;
    }

    public void update(BandData data) {
        this.data = data;
    }

    @Override
    protected void init() {
        scale = ClientConfig.screenScale(width, height, W, H);
        left = ((int) (width / scale) - W) / 2;
        top = ((int) (height / scale) - H) / 2;
        nameBox = new EditBox(font, left + 10, top + H - FOOT_H - 20, 100, 14, Component.translatable("screen.bsp_teleport.band.name_hint"));
        nameBox.setMaxLength(16);
        nameBox.setHint(Component.translatable("screen.bsp_teleport.band.name_hint"));
        nameBox.setBordered(true);
        addRenderableWidget(nameBox);
        nameBox.visible = tab == Tab.HOMES;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void send(BandAction action, String arg) {
        ClientNet.send(action, arg);
    }

    private void go(BandAction action, String arg) {
        send(action, arg);
        onClose();
    }

    // Drawing

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1f);
        hits.clear();
        g.fill(left - 1, top - 1, left + W + 1, top + H + 1, TQ);
        g.fill(left, top, left + W, top + H, BG);
        g.drawString(font, title.getString().toUpperCase(), left + 10, top + 8, TQ, false);
        small(g, Component.translatable("screen.bsp_teleport.band.help").getString(), left + 10, top + 19, MUTED);

        Tab[] tabs = data.admin() ? Tab.values() : new Tab[] {Tab.HOMES, Tab.WARPS, Tab.PLAYERS, Tab.MORE};
        int tabW = data.admin() ? 40 : TAB_W;
        for (int i = 0; i < tabs.length; i++) {
            int tx = left + W - 10 - (tabs.length - i) * (tabW + 2);
            boolean on = tabs[i] == tab;
            boolean admin = tabs[i] == Tab.ADMIN;
            g.fill(tx, top + 5, tx + tabW, top + 18, on ? TQ : admin ? VIO : DIM);
            g.fill(tx + 1, top + 6, tx + tabW - 1, top + 17, SLOT);
            String label = Component.translatable("screen.bsp_teleport.band.tab." + tabs[i].name().toLowerCase()).getString().toUpperCase();
            small(g, label, tx + (tabW - (int) (font.width(label) * 0.75f)) / 2, top + 8, on ? TQ : admin ? VIO : MUTED);
            Tab t = tabs[i];
            hits.add(new Hit(tx, top + 5, tabW, 13, () -> {
                if (t == Tab.ADMIN) {
                    send(BandAction.ADMIN, "");
                    return;
                }
                tab = t;
                scroll = 0;
                nameBox.visible = tab == Tab.HOMES;
            }));
        }

        int listBottom = top + H - FOOT_H - (tab == Tab.HOMES ? 24 : 4);
        if (data.raid()) {
            small(g, Component.translatable("screen.bsp_teleport.band.raid", data.raidDistance()).getString(), left + 10, top + LIST_TOP - 4, BAD);
        }
        int y = top + LIST_TOP + (data.raid() ? 8 : 0);
        switch (tab) {
            case HOMES -> drawHomes(g, y, listBottom);
            case WARPS -> drawWarps(g, y, listBottom);
            case PLAYERS -> drawPlayers(g, y, listBottom);
            case MORE -> drawMore(g, y, listBottom);
            case ADMIN -> {
            }
        }
        drawFooter(g);
        super.render(g, (int) (mouseX / scale), (int) (mouseY / scale), partial);
        g.pose().popPose();
    }

    private void drawHomes(GuiGraphics g, int y, int bottom) {
        if (data.homes().isEmpty()) {
            small(g, Component.translatable("screen.bsp_teleport.band.no_homes").getString(), left + 10, y + 2, MUTED);
        }
        int i = 0;
        for (BandData.HomeEntry h : data.homes()) {
            if (i++ < scroll) {
                continue;
            }
            if (y + ROW_H > bottom) {
                break;
            }
            String sub = where(h.server(), h.world(), h.x(), h.y(), h.z());
            int nameColour = h.locked() ? MUTED : h.nearRaid() ? BAD : TEXT;
            row(g, y, h.name(), sub, nameColour, h.server().equals(data.server()) ? MUTED : VIO);
            icon(g, y, h.icon(), com.mrgregles.bsp_teleport.util.Icons.DEFAULT_HOME, () -> send(BandAction.HOME_ICON, h.name()));
            int bx = left + W - 10;
            bx = button(g, bx, y + 4, Component.translatable("screen.bsp_teleport.band.del").getString(), DIM, MUTED, () -> send(BandAction.DEL_HOME, h.name()));
            if (h.locked()) {
                button(g, bx - 2, y + 4, Component.translatable("screen.bsp_teleport.band.locked").getString(), DIM, MUTED, null);
            } else if (h.nearRaid()) {
                button(g, bx - 2, y + 4, Component.translatable("screen.bsp_teleport.band.near_raid").getString(), BAD, BAD, null);
            } else {
                button(g, bx - 2, y + 4, Component.translatable("screen.bsp_teleport.band.go").getString(), TQ, TQ, () -> go(BandAction.HOME, h.name()));
            }
            y += ROW_H + 2;
        }
        // Set home here: name box plus button, above the footer.
        int sy = top + H - FOOT_H - 20;
        nameBox.setY(sy);
        button(g, left + W - 10, sy + 1, Component.translatable("screen.bsp_teleport.band.set_here").getString(), GOLD, GOLD, () -> {
            send(BandAction.SET_HOME, nameBox.getValue().trim());
            nameBox.setValue("");
        });
    }

    private void drawWarps(GuiGraphics g, int y, int bottom) {
        if (data.warps().isEmpty()) {
            small(g, Component.translatable("screen.bsp_teleport.band.no_warps").getString(), left + 10, y + 2, MUTED);
        }
        int i = 0;
        for (BandData.WarpEntry w : data.warps()) {
            if (i++ < scroll) {
                continue;
            }
            if (y + ROW_H > bottom) {
                break;
            }
            String sub = where(w.server(), w.world(), w.x(), w.y(), w.z());
            if (w.cost() > 0) {
                sub += "  " + Component.translatable("screen.bsp_teleport.band.coins", w.cost()).getString();
            }
            row(g, y, w.name(), sub, w.nearRaid() ? BAD : TEXT, w.server().equals(data.server()) ? MUTED : VIO);
            icon(g, y, w.icon(), com.mrgregles.bsp_teleport.util.Icons.DEFAULT_WARP, null);
            if (w.nearRaid()) {
                button(g, left + W - 10, y + 4, Component.translatable("screen.bsp_teleport.band.near_raid").getString(), BAD, BAD, null);
            } else {
                boolean afford = w.cost() <= data.coins();
                button(g, left + W - 10, y + 4, Component.translatable("screen.bsp_teleport.band.go").getString(), w.cost() > 0 ? GOLD : TQ, afford ? (w.cost() > 0 ? GOLD : TQ) : MUTED,
                        afford ? () -> go(BandAction.WARP, w.name()) : null);
            }
            y += ROW_H + 2;
        }
    }

    private void drawPlayers(GuiGraphics g, int y, int bottom) {
        BandData.RequestEntry r = data.request();
        if (r != null) {
            g.fill(left + 10, y, left + W - 10, y + ROW_H, VIO);
            g.fill(left + 11, y + 1, left + W - 11, y + ROW_H - 1, SLOT);
            String head = Component.translatable(r.here() ? "screen.bsp_teleport.band.request_to" : "screen.bsp_teleport.band.request", r.from()).getString();
            g.drawString(font, head, left + 14, y + 3, VIO, false);
            small(g, Component.translatable("screen.bsp_teleport.band.request_left", r.server(), r.secondsLeft()).getString(), left + 14, y + 12, MUTED);
            int bx = button(g, left + W - 14, y + 4, Component.translatable("screen.bsp_teleport.band.deny").getString(), BAD, BAD, () -> send(BandAction.DENY, ""));
            button(g, bx - 2, y + 4, Component.translatable("screen.bsp_teleport.band.accept").getString(), OK, OK, () -> go(BandAction.ACCEPT, ""));
            y += ROW_H + 4;
        }
        if (data.players().isEmpty()) {
            small(g, Component.translatable("screen.bsp_teleport.band.nobody").getString(), left + 10, y + 2, MUTED);
        }
        int i = 0;
        for (BandData.PlayerEntry p : data.players()) {
            if (i++ < scroll) {
                continue;
            }
            if (y + ROW_H > bottom) {
                break;
            }
            row(g, y, p.name(), p.server(), TEXT, p.server().equals(data.server()) ? MUTED : VIO);
            int bx = button(g, left + W - 10, y + 4, Component.translatable("screen.bsp_teleport.band.here").getString(), DIM, MUTED, () -> send(BandAction.TPA_HERE, p.name()));
            button(g, bx - 2, y + 4, Component.translatable("screen.bsp_teleport.band.tpa").getString(), TQ, TQ, () -> send(BandAction.TPA, p.name()));
            y += ROW_H + 2;
        }
    }

    private void drawMore(GuiGraphics g, int y, int bottom) {
        int x = left + 10;
        y = wide(g, x, y, Component.translatable("screen.bsp_teleport.band.spawn").getString(), data.hasSpawn() ? TQ : DIM, data.hasSpawn() ? () -> go(BandAction.SPAWN, "") : null);
        y = wide(g, x, y, Component.translatable("screen.bsp_teleport.band.back").getString(), data.hasBack() ? TQ : DIM, data.hasBack() ? () -> go(BandAction.BACK, "") : null);
        y = wide(g, x, y, Component.translatable("screen.bsp_teleport.band.rtp_here").getString(), TQ, () -> go(BandAction.RTP, ""));
        for (String server : data.rtpServers()) {
            if (y + 16 > bottom) {
                break;
            }
            y = wide(g, x, y, Component.translatable("screen.bsp_teleport.band.rtp_on", server).getString(), VIO, () -> go(BandAction.RTP, server));
        }
        y += 4;
        y = wide(g, x, y, Component.translatable(data.ignoring() ? "screen.bsp_teleport.band.ignore_on" : "screen.bsp_teleport.band.ignore_off").getString(),
                data.ignoring() ? GOLD : DIM, () -> send(BandAction.TOGGLE_IGNORE, ""));
        if (data.skipCoins() > 0 && data.cooldownLeft() > 0) {
            wide(g, x, y, Component.translatable("screen.bsp_teleport.band.tpnow", data.skipCoins()).getString(), GOLD, () -> go(BandAction.TPNOW, ""));
        }
    }

    private void drawFooter(GuiGraphics g) {
        int y = top + H - FOOT_H + 4;
        g.fill(left + 10, y - 3, left + W - 10, y - 2, DIM);
        String cd = data.cooldownLeft() > 0 ? Component.translatable("screen.bsp_teleport.band.cooldown", data.cooldownLeft()).getString()
                : Component.translatable("screen.bsp_teleport.band.ready").getString();
        small(g, cd, left + 10, y, data.cooldownLeft() > 0 ? BAD : OK);
        String slots = Component.translatable("screen.bsp_teleport.band.slots", data.used(), data.slots()).getString();
        small(g, slots, left + 10, y + 9, data.used() > data.slots() ? BAD : TEXT);
        String next = data.extraHomesLevel() >= 3 ? Component.translatable("screen.bsp_teleport.band.slots_max").getString()
                : Component.translatable("screen.bsp_teleport.band.next_slot", data.extraHomesLevel() + 1).getString();
        small(g, next, left + 100, y + 9, MUTED);
        if (data.swiftLevel() > 0) {
            small(g, Component.translatable("screen.bsp_teleport.band.swift", data.swiftLevel(), data.swiftLevel() * 25).getString(), left + 100, y, MUTED);
        }
    }

    // Pieces

    private void row(GuiGraphics g, int y, String name, String sub, int nameColour, int subColour) {
        g.fill(left + 10, y, left + W - 10, y + ROW_H, DIM);
        g.fill(left + 11, y + 1, left + W - 11, y + ROW_H - 1, SLOT);
        boolean withIcon = tab == Tab.HOMES || tab == Tab.WARPS;
        int tx = withIcon ? left + 32 : left + 14;
        g.drawString(font, name, tx, y + 3, nameColour, false);
        small(g, sub, tx, y + 12, subColour);
    }

    /** The item icon at the row's left; clicking it (when {@code onClick} is given) makes it the held item. */
    private void icon(GuiGraphics g, int y, String icon, String fallback, @Nullable Runnable onClick) {
        g.renderItem(com.mrgregles.bsp_teleport.util.Icons.stack(icon, fallback), left + 13, y + 2);
        if (onClick != null) {
            hits.add(new Hit(left + 12, y + 1, 18, 18, onClick));
        }
    }

    /** A small right-aligned button; returns the x where the next one to its left ends. */
    private int button(GuiGraphics g, int rightX, int y, String label, int border, int colour, Runnable action) {
        int w = (int) (font.width(label) * 0.75f) + 8;
        int x = rightX - w;
        g.fill(x, y, x + w, y + 12, border);
        g.fill(x + 1, y + 1, x + w - 1, y + 11, SLOT);
        small(g, label.toUpperCase(), x + 4, y + 3, colour);
        if (action != null) {
            hits.add(new Hit(x, y, w, 12, action));
        }
        return x;
    }

    private int wide(GuiGraphics g, int x, int y, String label, int colour, Runnable action) {
        int w = W - 20;
        g.fill(x, y, x + w, y + 14, colour == DIM ? DIM : colour);
        g.fill(x + 1, y + 1, x + w - 1, y + 13, SLOT);
        g.drawString(font, label, x + 5, y + 3, colour == DIM ? MUTED : colour, false);
        if (action != null) {
            hits.add(new Hit(x, y, w, 14, action));
        }
        return y + 16;
    }

    private void small(GuiGraphics g, String text, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().scale(0.75f, 0.75f, 1f);
        g.drawString(font, text, (int) (x / 0.75f), (int) (y / 0.75f), colour, false);
        g.pose().popPose();
    }

    private String where(String server, String world, int x, int y, int z) {
        String w = world.startsWith("minecraft:") ? world.substring(10) : world;
        String s = server.equals(data.server()) ? "" : server + "  ";
        return s + w + "  " + x + ", " + y + ", " + z;
    }

    // Input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        mx /= scale;
        my /= scale;
        if (button == 0) {
            for (Hit hit : hits) {
                if (hit.contains(mx, my)) {
                    hit.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        return super.mouseReleased(mx / scale, my / scale, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return super.mouseDragged(mx / scale, my / scale, button, dx / scale, dy / scale);
    }

    @Override
    public void mouseMoved(double mx, double my) {
        super.mouseMoved(mx / scale, my / scale);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int count = switch (tab) {
            case HOMES -> data.homes().size();
            case WARPS -> data.warps().size();
            case PLAYERS -> data.players().size();
            case MORE, ADMIN -> 0;
        };
        scroll = Math.max(0, Math.min(Math.max(0, count - 1), scroll - (int) Math.signum(delta)));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (nameBox.isFocused() && key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) {
            send(BandAction.SET_HOME, nameBox.getValue().trim());
            nameBox.setValue("");
            return true;
        }
        if (!nameBox.isFocused() && (BandClient.OPEN_KEY.matches(key, scan) || minecraft.options.keyInventory.matches(key, scan))) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}
