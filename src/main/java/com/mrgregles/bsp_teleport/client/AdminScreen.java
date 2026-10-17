package com.mrgregles.bsp_teleport.client;

import com.mrgregles.bsp_teleport.admin.AdminAction;
import com.mrgregles.bsp_teleport.admin.AdminData;
import com.mrgregles.bsp_teleport.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The admin panel, Map layout: the network drawn as nodes (this server gold, a down server red), a detail pane for the
 * selected server with its players, and SETTINGS and WARPS tabs. Anything that moves more than one player, and
 * deleting a warp, asks "are you sure" in a popup first. Moderators see everything and can press nothing.
 */
public class AdminScreen extends Screen {
    private static final int W = 300, H = 214, TAB_W = 50;
    private static final int TQ = BandClient.TQ, BG = BandClient.BG, SLOT = BandClient.SLOT, DIM = BandClient.DIM, MUTED = BandClient.MUTED,
            TEXT = BandClient.TEXT, VIO = BandClient.VIO, GOLD = BandClient.GOLD, BAD = BandClient.BAD, OK = BandClient.OK;

    private enum Tab { NETWORK, SETTINGS, WARPS }

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /** The popup: a question and what a Yes does. */
    private record Confirm(String question, Runnable yes) {
    }

    private AdminData data;
    private Tab tab = Tab.NETWORK;
    private String selectedServer;
    private boolean pickingTarget;
    private int scroll;
    private int left, top;
    /** Everything is drawn this many times bigger than vanilla's GUI scale; mouse positions are divided by it. */
    private float scale = 1f;
    private final List<Hit> hits = new ArrayList<>();
    @Nullable
    private Confirm confirm;
    private EditBox nameBox, textBox;

    public AdminScreen(AdminData data) {
        super(Component.translatable("screen.bsp_teleport.admin.title"));
        this.data = data;
        this.selectedServer = data.server();
    }

    public static void show(AdminData data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof AdminScreen screen) {
            screen.update(data);
        } else {
            mc.setScreen(new AdminScreen(data));
        }
    }

    public void update(AdminData data) {
        this.data = data;
        if (data.servers().stream().noneMatch(s -> s.name().equals(selectedServer))) {
            selectedServer = data.server();
        }
    }

    @Override
    protected void init() {
        scale = ClientConfig.screenScale(width, height, W, H);
        left = ((int) (width / scale) - W) / 2;
        top = ((int) (height / scale) - H) / 2;
        nameBox = new EditBox(font, left + 10, top + H - 40, 90, 14, Component.literal("name"));
        nameBox.setMaxLength(16);
        nameBox.setHint(Component.translatable("screen.bsp_teleport.band.name_hint"));
        addRenderableWidget(nameBox);
        textBox = new EditBox(font, left + 10, top + H - 40, 120, 14, Component.literal("value"));
        textBox.setMaxLength(16);
        addRenderableWidget(textBox);
        refreshBoxes();
    }

    private void refreshBoxes() {
        nameBox.visible = tab == Tab.WARPS && !data.readOnly();
        textBox.visible = tab == Tab.SETTINGS && !data.readOnly();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void send(AdminAction action, String arg) {
        ClientNet.sendAdmin(action, arg);
    }

    private void go(AdminAction action, String arg) {
        send(action, arg);
        onClose();
    }

    @Nullable
    private AdminData.ServerRow selected() {
        return data.servers().stream().filter(s -> s.name().equals(selectedServer)).findFirst().orElse(null);
    }

    // Drawing

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1f);
        hits.clear();
        g.fill(left - 1, top - 1, left + W + 1, top + H + 1, VIO);
        g.fill(left, top, left + W, top + H, BG);
        g.drawString(font, title.getString().toUpperCase(), left + 10, top + 8, VIO, false);
        small(g, Component.translatable(data.readOnly() ? "screen.bsp_teleport.admin.readonly" : "screen.bsp_teleport.admin.help").getString(), left + 10, top + 19, data.readOnly() ? GOLD : MUTED);

        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            int tx = left + W - 10 - (tabs.length - i) * (TAB_W + 2);
            boolean on = tabs[i] == tab;
            g.fill(tx, top + 5, tx + TAB_W, top + 18, on ? VIO : DIM);
            g.fill(tx + 1, top + 6, tx + TAB_W - 1, top + 17, SLOT);
            String label = Component.translatable("screen.bsp_teleport.admin.tab." + tabs[i].name().toLowerCase()).getString().toUpperCase();
            small(g, label, tx + (TAB_W - (int) (font.width(label) * 0.75f)) / 2, top + 8, on ? VIO : MUTED);
            Tab t = tabs[i];
            hits.add(new Hit(tx, top + 5, TAB_W, 13, () -> {
                tab = t;
                scroll = 0;
                pickingTarget = false;
                refreshBoxes();
            }));
        }

        switch (tab) {
            case NETWORK -> drawNetwork(g);
            case SETTINGS -> drawSettings(g);
            case WARPS -> drawWarps(g);
        }
        drawFooter(g);
        super.render(g, (int) (mouseX / scale), (int) (mouseY / scale), partial);
        if (confirm != null) {
            drawConfirm(g);
        }
        g.pose().popPose();
    }

    private void drawNetwork(GuiGraphics g) {
        int sx = left + 10, sy = top + 28, sw = W - 20, sh = 92;
        g.fill(sx, sy, sx + sw, sy + sh, DIM);
        g.fill(sx + 1, sy + 1, sx + sw - 1, sy + sh - 1, SLOT);
        List<AdminData.ServerRow> servers = data.servers();
        int n = servers.size();
        int cx = sx + sw / 2, cy = sy + sh / 2;
        int[] nx = new int[n], ny = new int[n];
        for (int i = 0; i < n; i++) {
            double a = n == 1 ? 0 : -Math.PI / 2 + i * Math.PI * 2 / n;
            nx[i] = n == 1 ? cx : cx + (int) (Math.cos(a) * (sw / 2 - 48));
            ny[i] = n == 1 ? cy : cy + (int) (Math.sin(a) * (sh / 2 - 16));
        }
        // Lines from this server to the others.
        int hereIndex = 0;
        for (int i = 0; i < n; i++) {
            if (servers.get(i).here()) {
                hereIndex = i;
            }
        }
        for (int i = 0; i < n; i++) {
            if (i != hereIndex) {
                line(g, nx[hereIndex], ny[hereIndex], nx[i], ny[i], DIM);
            }
        }
        for (int i = 0; i < n; i++) {
            AdminData.ServerRow s = servers.get(i);
            boolean on = s.name().equals(selectedServer);
            int bw = Math.max(64, (int) (font.width(s.name()) * 0.75f) + 12), bh = 20;
            int bx = nx[i] - bw / 2, by = ny[i] - bh / 2;
            int border = s.here() ? GOLD : !s.up() ? BAD : TQ;
            g.fill(bx, by, bx + bw, by + bh, border);
            g.fill(bx + 1, by + 1, bx + bw - 1, by + bh - 1, on ? 0xFF0F2A28 : BG);
            small(g, s.name(), bx + 4, by + 3, on ? TQ : TEXT);
            String count = s.up() ? Component.translatable("screen.bsp_teleport.admin.players", s.players().size()).getString()
                    : Component.translatable("screen.bsp_teleport.admin.restarting").getString();
            small(g, count, bx + 4, by + 11, s.up() ? MUTED : BAD);
            hits.add(new Hit(bx, by, bw, bh, () -> {
                selectedServer = s.name();
                pickingTarget = false;
                scroll = 0;
            }));
        }

        AdminData.ServerRow sel = selected();
        if (sel == null) {
            return;
        }
        int y = sy + sh + 6;
        g.fill(sx, y, sx + sw, y + 22, DIM);
        g.fill(sx + 1, y + 1, sx + sw - 1, y + 21, SLOT);
        g.drawString(font, sel.name(), sx + 4, y + 3, sel.here() ? GOLD : TEXT, false);
        String who = sel.players().isEmpty() ? Component.translatable("screen.bsp_teleport.admin.nobody").getString() : String.join(", ", sel.players());
        small(g, clip(who, 150), sx + 4, y + 13, MUTED);
        if (!data.readOnly()) {
            int bx = sx + sw - 4;
            if (pickingTarget) {
                for (AdminData.ServerRow other : data.servers()) {
                    if (!other.name().equals(sel.name()) && other.up()) {
                        String label = Component.translatable("screen.bsp_teleport.admin.send_to", other.name()).getString();
                        bx = button(g, bx, y + 5, label, VIO, VIO, () -> ask(
                                Component.translatable("screen.bsp_teleport.admin.confirm_send", sel.players().size(), sel.name(), other.name()).getString(),
                                () -> go(AdminAction.SEND_ALL, sel.name() + ";" + other.name()))) - 2;
                    }
                }
            } else if (sel.here()) {
                bx = button(g, bx, y + 5, Component.translatable("screen.bsp_teleport.admin.send_all").getString(), VIO, VIO, () -> pickingTarget = true) - 2;
                button(g, bx, y + 5, Component.translatable("screen.bsp_teleport.admin.bring_everyone").getString(), VIO, VIO, () -> ask(
                        Component.translatable("screen.bsp_teleport.admin.confirm_bring_all").getString(), () -> go(AdminAction.BRING_ALL, "*")));
            } else if (sel.up()) {
                bx = button(g, bx, y + 5, Component.translatable("screen.bsp_teleport.admin.go").getString(), TQ, TQ, () -> go(AdminAction.GO_SERVER, sel.name())) - 2;
                bx = button(g, bx, y + 5, Component.translatable("screen.bsp_teleport.admin.send_all").getString(), DIM, MUTED, () -> pickingTarget = true) - 2;
                button(g, bx, y + 5, Component.translatable("screen.bsp_teleport.admin.bring_all").getString(), VIO, VIO, () -> ask(
                        Component.translatable("screen.bsp_teleport.admin.confirm_bring", sel.players().size(), sel.name()).getString(),
                        () -> go(AdminAction.BRING_ALL, sel.name())));
            }
        }
        // The selected server's players, scrollable.
        y += 26;
        int bottom = top + H - 24;
        int i = 0;
        for (String name : sel.players()) {
            if (i++ < scroll) {
                continue;
            }
            if (y + 16 > bottom) {
                break;
            }
            g.fill(sx, y, sx + sw, y + 14, DIM);
            g.fill(sx + 1, y + 1, sx + sw - 1, y + 13, SLOT);
            g.drawString(font, name, sx + 4, y + 3, TEXT, false);
            if (!data.readOnly()) {
                int bx = sx + sw - 4;
                bx = button(g, bx, y + 1, Component.translatable("screen.bsp_teleport.admin.last").getString(), DIM, MUTED, () -> go(AdminAction.GO_OFFLINE, name)) - 2;
                bx = button(g, bx, y + 1, Component.translatable("screen.bsp_teleport.admin.go_to").getString(), TQ, TQ, () -> go(AdminAction.GO_PLAYER, name)) - 2;
                button(g, bx, y + 1, Component.translatable("screen.bsp_teleport.admin.bring").getString(), VIO, VIO, () -> go(AdminAction.BRING_PLAYER, name));
            }
            y += 16;
        }
    }

    private void drawSettings(GuiGraphics g) {
        int y = top + 28;
        int bottom = top + H - 44;
        int i = 0;
        for (AdminData.Setting s : data.settings()) {
            if (i++ < scroll) {
                continue;
            }
            if (y + 16 > bottom) {
                break;
            }
            g.fill(left + 10, y, left + W - 10, y + 14, DIM);
            g.fill(left + 11, y + 1, left + W - 11, y + 13, SLOT);
            small(g, Component.translatable("screen.bsp_teleport.admin.setting." + s.id()).getString(), left + 14, y + 4, TEXT);
            int bx = left + W - 14;
            switch (s.kind()) {
                case "BOOL" -> {
                    boolean on = Boolean.parseBoolean(s.value());
                    button(g, bx, y + 1, Component.translatable(on ? "screen.bsp_teleport.admin.on" : "screen.bsp_teleport.admin.off").getString(), on ? OK : DIM, on ? OK : MUTED,
                            data.readOnly() ? null : () -> send(AdminAction.SET_SETTING, s.id() + ";" + !on));
                }
                case "INT" -> {
                    int v = parse(s.value());
                    bx = button(g, bx, y + 1, "+", DIM, TQ, data.readOnly() ? null : () -> send(AdminAction.SET_SETTING, s.id() + ";" + Math.min(s.max(), v + s.step()))) - 2;
                    String text = s.value();
                    int tw = (int) (font.width(text) * 0.75f) + 6;
                    small(g, text, bx - tw + 3, y + 4, GOLD);
                    bx -= tw + 2;
                    button(g, bx, y + 1, "-", DIM, TQ, data.readOnly() ? null : () -> send(AdminAction.SET_SETTING, s.id() + ";" + Math.max(s.min(), v - s.step())));
                }
                default -> {
                    small(g, clip(s.value().isEmpty() ? "-" : s.value(), 90), bx - 110, y + 4, GOLD);
                    if (!data.readOnly()) {
                        button(g, bx, y + 1, Component.translatable("screen.bsp_teleport.admin.set").getString(), GOLD, GOLD, () -> {
                            send(AdminAction.SET_SETTING, s.id() + ";" + textBox.getValue().trim());
                            textBox.setValue("");
                        });
                    }
                }
            }
            y += 16;
        }
        textBox.setY(top + H - 42);
        if (!data.readOnly()) {
            small(g, Component.translatable("screen.bsp_teleport.admin.saved").getString(), left + 136, top + H - 38, MUTED);
            button(g, left + W - 10, top + H - 41, Component.translatable("screen.bsp_teleport.admin.reload").getString(), DIM, MUTED, () -> send(AdminAction.RELOAD, ""));
        }
    }

    private void drawWarps(GuiGraphics g) {
        int y = top + 28;
        int bottom = top + H - 44;
        int i = 0;
        for (AdminData.WarpRow w : data.warps()) {
            if (i++ < scroll) {
                continue;
            }
            if (y + 18 > bottom) {
                break;
            }
            g.fill(left + 10, y, left + W - 10, y + 16, DIM);
            g.fill(left + 11, y + 1, left + W - 11, y + 15, SLOT);
            g.renderItem(com.mrgregles.bsp_teleport.util.Icons.stack(w.icon(), com.mrgregles.bsp_teleport.util.Icons.DEFAULT_WARP), left + 12, y);
            if (!data.readOnly()) {
                hits.add(new Hit(left + 11, y, 18, 16, () -> send(AdminAction.WARP_ICON, w.name())));
            }
            g.drawString(font, w.name(), left + 30, y + 4, TEXT, false);
            String sub = (w.server().equals(data.server()) ? "" : w.server() + "  ") + (w.cost() > 0 ? w.cost() + "c  " : "")
                    + Component.translatable(w.restricted() ? "screen.bsp_teleport.admin.restricted" : "screen.bsp_teleport.admin.open").getString();
            small(g, sub, left + 30 + font.width(w.name()) + 6, y + 5, w.server().equals(data.server()) ? MUTED : VIO);
            int bx = left + W - 14;
            if (!data.readOnly()) {
                bx = button(g, bx, y + 2, Component.translatable("screen.bsp_teleport.admin.del").getString(), BAD, BAD, () -> ask(
                        Component.translatable("screen.bsp_teleport.admin.confirm_del", w.name()).getString(), () -> send(AdminAction.DEL_WARP, w.name()))) - 2;
                bx = button(g, bx, y + 2, w.restricted() ? "R" : "O", w.restricted() ? VIO : DIM, w.restricted() ? VIO : MUTED, () -> send(AdminAction.WARP_RESTRICT, w.name() + ";" + !w.restricted())) - 2;
                bx = button(g, bx, y + 2, "+", DIM, GOLD, () -> send(AdminAction.WARP_COST, w.name() + ";" + (w.cost() + 10))) - 2;
                bx = button(g, bx, y + 2, "-", DIM, GOLD, () -> send(AdminAction.WARP_COST, w.name() + ";" + Math.max(0, w.cost() - 10))) - 2;
                bx = button(g, bx, y + 2, Component.translatable("screen.bsp_teleport.admin.move_here").getString(), DIM, MUTED, () -> send(AdminAction.MOVE_WARP, w.name())) - 2;
            }
            button(g, bx, y + 2, Component.translatable("screen.bsp_teleport.admin.go").getString(), TQ, TQ, () -> go(AdminAction.GO_WARP, w.name()));
            y += 18;
        }
        nameBox.setY(top + H - 42);
        if (!data.readOnly()) {
            button(g, left + W - 10, top + H - 41, Component.translatable("screen.bsp_teleport.admin.set_warp").getString(), GOLD, GOLD, () -> {
                send(AdminAction.SET_WARP, nameBox.getValue().trim() + ";0;false");
                nameBox.setValue("");
            });
        }
    }

    private void drawFooter(GuiGraphics g) {
        int y = top + H - 20;
        g.fill(left + 10, y - 2, left + W - 10, y - 1, DIM);
        small(g, data.server() + "   " + data.storage(), left + 10, y + 2, MUTED);
        small(g, data.messaging(), left + 10, y + 10, data.messaging().contains("listening") ? OK : MUTED);
    }

    private void drawConfirm(GuiGraphics g) {
        int cw = 220, ch = 56;
        int cx = left + (W - cw) / 2, cy = top + (H - ch) / 2;
        g.fill(left, top, left + W, top + H, 0xA0000000);
        g.fill(cx - 1, cy - 1, cx + cw + 1, cy + ch + 1, GOLD);
        g.fill(cx, cy, cx + cw, cy + ch, BG);
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.literal(confirm.question()), cw - 16);
        int ty = cy + 8;
        for (net.minecraft.util.FormattedCharSequence line : lines) {
            g.drawString(font, line, cx + 8, ty, TEXT, false);
            ty += 10;
        }
        hits.clear(); // only the popup's buttons answer while it is up
        int bx = cx + cw - 8;
        bx = button(g, bx, cy + ch - 18, Component.translatable("screen.bsp_teleport.admin.no").getString(), DIM, MUTED, () -> confirm = null) - 4;
        Confirm c = confirm;
        button(g, bx, cy + ch - 18, Component.translatable("screen.bsp_teleport.admin.yes").getString(), OK, OK, () -> {
            confirm = null;
            c.yes().run();
        });
    }

    private void ask(String question, Runnable yes) {
        confirm = new Confirm(question, yes);
    }

    // Pieces

    private int button(GuiGraphics g, int rightX, int y, String label, int border, int colour, @Nullable Runnable action) {
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

    private void small(GuiGraphics g, String text, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().scale(0.75f, 0.75f, 1f);
        g.drawString(font, text, (int) (x / 0.75f), (int) (y / 0.75f), colour, false);
        g.pose().popPose();
    }

    private void line(GuiGraphics g, int x1, int y1, int x2, int y2, int colour) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        for (int i = 0; i <= steps; i += 2) {
            int x = x1 + (x2 - x1) * i / Math.max(1, steps), y = y1 + (y2 - y1) * i / Math.max(1, steps);
            g.fill(x, y, x + 1, y + 1, colour);
        }
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

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // Input

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
            if (confirm != null) {
                return true;
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
        AdminData.ServerRow sel = selected();
        int count = switch (tab) {
            case NETWORK -> sel == null ? 0 : sel.players().size();
            case SETTINGS -> data.settings().size();
            case WARPS -> data.warps().size();
        };
        scroll = Math.max(0, Math.min(Math.max(0, count - 1), scroll - (int) Math.signum(delta)));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (confirm != null && key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            confirm = null;
            return true;
        }
        if (!nameBox.isFocused() && !textBox.isFocused() && minecraft.options.keyInventory.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}
