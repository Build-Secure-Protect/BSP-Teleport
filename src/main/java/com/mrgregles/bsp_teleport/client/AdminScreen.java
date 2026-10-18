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
    private static final int W = 300, H = 214, TAB_W = 46;
    private static final int TQ = BandClient.TQ, BG = BandClient.BG, SLOT = BandClient.SLOT, DIM = BandClient.DIM, MUTED = BandClient.MUTED,
            TEXT = BandClient.TEXT, VIO = BandClient.VIO, GOLD = BandClient.GOLD, BAD = BandClient.BAD, OK = BandClient.OK;

    private enum Tab { NETWORK, SETTINGS, WARPS, PORTALS, TOKENS }

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
    /** PORTALS tab: the node map instead of the grouped list (D66), and the selected portal on it. */
    private boolean portalMap;
    private String selectedPortal = "";
    /** TOKENS tab (D89, pick then place): the token picked on the left, 0 for none. */
    private int pickedToken;
    /** WARPS tab: the warp whose rules are being edited (D86), or null for the list. */
    @Nullable
    private String accessWarp;
    private String warpRuleKind = "RANK";
    private boolean warpRuleConsume;
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
        nameBox.visible = (tab == Tab.WARPS || tab == Tab.TOKENS) && !data.readOnly();
        textBox.visible = (tab == Tab.SETTINGS || tab == Tab.TOKENS || (tab == Tab.WARPS && accessWarp != null)) && !data.readOnly();
        nameBox.setHint(Component.translatable(tab == Tab.TOKENS ? "screen.bsp_teleport.admin.region_id" : "screen.bsp_teleport.band.name_hint"));
        nameBox.setMaxLength(tab == Tab.TOKENS ? 64 : 16);
        textBox.setMaxLength(tab == Tab.TOKENS ? 64 : 16);
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
            case PORTALS -> drawPortals(g);
            case TOKENS -> drawTokens(g);
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
        g.fill(sx, y, sx + sw, y + 34, DIM);
        g.fill(sx + 1, y + 1, sx + sw - 1, y + 33, SLOT);
        g.drawString(font, sel.name(), sx + 4, y + 3, sel.here() ? GOLD : TEXT, false);
        // D70: whether this server needs access, written to the shared list.
        boolean needs = data.restricted().stream().anyMatch(r -> r.equalsIgnoreCase(sel.name()));
        String needsLabel = Component.translatable("screen.bsp_teleport.admin.needs_access").getString() + ": " + Component.translatable(needs ? "screen.bsp_teleport.admin.on" : "screen.bsp_teleport.admin.off").getString();
        button(g, sx + sw - 4, y + 20, needsLabel, needs ? GOLD : DIM, needs ? GOLD : MUTED, data.readOnly() ? null : () -> send(AdminAction.ACCESS_SERVER, sel.name() + ";" + !needs));
        if (!data.accessOn()) {
            small(g, Component.translatable("screen.bsp_teleport.admin.access_off").getString(), sx + 4, y + 24, MUTED);
        }
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
        y += 38;
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
        if (accessWarp != null) {
            AdminData.WarpRow editing = data.warps().stream().filter(w -> w.name().equals(accessWarp)).findFirst().orElse(null);
            if (editing == null) {
                accessWarp = null;
                refreshBoxes();
            } else {
                drawWarpRules(g, editing);
                return;
            }
        }
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
                    + Component.translatable(w.restricted() ? "screen.bsp_teleport.admin.restricted" : "screen.bsp_teleport.admin.open").getString()
                    + (w.portal() ? "  " + Component.translatable("screen.bsp_teleport.admin.portal").getString() : "");
            small(g, sub, left + 30 + font.width(w.name()) + 6, y + 5, w.portal() ? VIO : w.server().equals(data.server()) ? MUTED : VIO);
            int bx = left + W - 14;
            if (!data.readOnly()) {
                bx = button(g, bx, y + 2, Component.translatable("screen.bsp_teleport.admin.del").getString(), BAD, BAD, () -> ask(
                        Component.translatable("screen.bsp_teleport.admin.confirm_del", w.name()).getString(), () -> send(AdminAction.DEL_WARP, w.name()))) - 2;
                bx = button(g, bx, y + 2, Component.translatable("screen.bsp_teleport.admin.access_btn").getString(), w.rules().isEmpty() ? DIM : VIO, w.rules().isEmpty() ? MUTED : VIO, () -> {
                    accessWarp = w.name();
                    scroll = 0;
                    refreshBoxes();
                }) - 2;
                bx = button(g, bx, y + 2, "P", w.portal() ? VIO : DIM, w.portal() ? VIO : MUTED, () -> send(AdminAction.WARP_PORTAL, w.name() + ";" + !w.portal())) - 2;
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

    /** The rules of one warp (D86): the list with Del, then a row to add one. */
    private void drawWarpRules(GuiGraphics g, AdminData.WarpRow w) {
        int y = top + 28;
        small(g, Component.translatable("screen.bsp_teleport.admin.rules_of", w.name()).getString().toUpperCase(), left + 10, y, VIO);
        button(g, left + W - 10, y - 2, Component.translatable("screen.bsp_teleport.admin.back").getString(), DIM, MUTED, () -> {
            accessWarp = null;
            refreshBoxes();
        });
        y += 12;
        small(g, Component.translatable("screen.bsp_teleport.portal.rules_help").getString(), left + 10, y, MUTED);
        y += 10;
        List<com.mrgregles.bsp_teleport.access.AccessRule> rules = com.mrgregles.bsp_teleport.access.AccessRule.parseList(w.rules());
        if (rules.isEmpty()) {
            small(g, Component.translatable("screen.bsp_teleport.portal.rules_none").getString(), left + 12, y + 2, DIM);
            y += 14;
        }
        for (int i = 0; i < rules.size(); i++) {
            g.fill(left + 10, y, left + W - 10, y + 12, DIM);
            g.fill(left + 11, y + 1, left + W - 11, y + 11, SLOT);
            small(g, rules.get(i).describe(), left + 14, y + 3, TEXT);
            if (!data.readOnly()) {
                int index = i;
                button(g, left + W - 14, y, Component.translatable("screen.bsp_teleport.admin.del").getString(), BAD, BAD, () -> send(AdminAction.WARP_RULE_DEL, w.name() + ";" + index));
            }
            y += 14;
        }
        if (data.readOnly()) {
            return;
        }
        y += 4;
        int px = left + 10;
        for (String kind : new String[]{"RANK", "ITEM", "NODE", "COINS", "TOKEN"}) {
            boolean on = kind.equals(warpRuleKind);
            String label = Component.translatable("screen.bsp_teleport.portal.rule." + kind.toLowerCase()).getString();
            int pw = (int) (font.width(label) * 0.75f) + 8;
            g.fill(px, y, px + pw, y + 12, on ? VIO : DIM);
            g.fill(px + 1, y + 1, px + pw - 1, y + 11, SLOT);
            small(g, label.toUpperCase(), px + 4, y + 3, on ? VIO : MUTED);
            hits.add(new Hit(px, y, pw, 12, () -> warpRuleKind = kind));
            px += pw + 3;
        }
        y += 16;
        nameBox.setX(left + 12);
        nameBox.setY(y);
        nameBox.setWidth(120);
        nameBox.setHint(Component.translatable("screen.bsp_teleport.portal.rule_value"));
        textBox.setX(left + 136);
        textBox.setY(y);
        textBox.setWidth(36);
        textBox.setHint(Component.translatable("screen.bsp_teleport.portal.rule_count"));
        nameBox.visible = warpRuleKind.equals("RANK") || warpRuleKind.equals("NODE") || warpRuleKind.equals("ITEM");
        textBox.visible = warpRuleKind.equals("ITEM") || warpRuleKind.equals("COINS");
        if (warpRuleKind.equals("ITEM")) {
            button(g, left + 230, y + 1, Component.translatable("screen.bsp_teleport.portal.rule_consume").getString(), warpRuleConsume ? OK : DIM, warpRuleConsume ? OK : MUTED, () -> warpRuleConsume = !warpRuleConsume);
        } else if (warpRuleKind.equals("TOKEN")) {
            small(g, Component.translatable("screen.bsp_teleport.admin.kind.warp").getString() + " " + w.name() + ": " + Component.translatable("screen.bsp_teleport.admin.tokens_list").getString().toLowerCase(), left + 12, y + 3, MUTED);
        }
        button(g, left + W - 14, y + 1, Component.translatable("screen.bsp_teleport.portal.rule_add").getString(), TQ, TQ, () -> {
            send(AdminAction.WARP_RULE_ADD, w.name() + ";" + warpRuleKind + ";" + nameBox.getValue().trim() + ";" + textBox.getValue().trim() + ";" + warpRuleConsume);
            nameBox.setValue("");
            textBox.setValue("");
        });
    }

    private void drawPortals(GuiGraphics g) {
        int y = top + 28;
        small(g, Component.translatable("screen.bsp_teleport.admin.portals_help").getString(), left + 10, y, MUTED);
        button(g, left + W - 10, y - 2, Component.translatable(portalMap ? "screen.bsp_teleport.admin.list" : "screen.bsp_teleport.admin.map").getString(), VIO, VIO, () -> {
            portalMap = !portalMap;
            scroll = 0;
        });
        y += 12;
        List<AdminData.PortalEntry> portals = data.portals();
        if (portals.isEmpty()) {
            small(g, Component.translatable("screen.bsp_teleport.admin.portals_none").getString(), left + 12, y + 4, MUTED);
            return;
        }
        int bottom = top + H - 24;
        if (portalMap) {
            int sx = left + 10, sw = W - 20, sh = 96;
            g.fill(sx, y, sx + sw, y + sh, DIM);
            g.fill(sx + 1, y + 1, sx + sw - 1, y + sh - 1, SLOT);
            int n = portals.size(), cx = sx + sw / 2, cy = y + sh / 2;
            int[] nx = new int[n], ny = new int[n];
            for (int i = 0; i < n; i++) {
                double a = -Math.PI / 2 + i * Math.PI * 2 / n;
                nx[i] = n == 1 ? cx : cx + (int) (Math.cos(a) * (sw / 2 - 52));
                ny[i] = n == 1 ? cy : cy + (int) (Math.sin(a) * (sh / 2 - 14));
            }
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (!portals.get(i).linkId().isEmpty() && portals.get(i).linkId().equals(portals.get(j).linkId())) {
                        line(g, nx[i], ny[i], nx[j], ny[j], VIO);
                    }
                }
            }
            if (selectedPortal.isEmpty() || portals.stream().noneMatch(p -> p.id().equals(selectedPortal))) {
                selectedPortal = portals.get(0).id();
            }
            for (int i = 0; i < n; i++) {
                AdminData.PortalEntry p = portals.get(i);
                boolean on = p.id().equals(selectedPortal);
                int bw = Math.max(60, (int) (font.width(p.name()) * 0.75f) + 10), bh = 18;
                int bx = nx[i] - bw / 2, by = ny[i] - bh / 2;
                int border = portalColour(p);
                g.fill(bx, by, bx + bw, by + bh, border);
                g.fill(bx + 1, by + 1, bx + bw - 1, by + bh - 1, on ? 0xFF0F2A28 : BG);
                small(g, clip(p.name(), bw - 6), bx + 3, by + 2, on ? TQ : TEXT);
                small(g, clip(p.server(), bw - 6), bx + 3, by + 10, p.server().equals(data.server()) ? GOLD : MUTED);
                hits.add(new Hit(bx, by, bw, bh, () -> selectedPortal = p.id()));
            }
            y += sh + 4;
            for (AdminData.PortalEntry p : portals) {
                if (p.id().equals(selectedPortal)) {
                    portalRow(g, y, p, true);
                }
            }
            return;
        }
        String lastServer = null;
        int i = 0;
        for (AdminData.PortalEntry p : portals) {
            if (!p.server().equals(lastServer)) {
                lastServer = p.server();
                if (i++ >= scroll) {
                    if (y + 10 > bottom) {
                        break;
                    }
                    small(g, p.server().toUpperCase() + (p.server().equals(data.server()) ? "  (" + Component.translatable("screen.bsp_teleport.admin.this_server").getString() + ")" : ""), left + 12, y, GOLD);
                    y += 10;
                }
            }
            if (i++ < scroll) {
                continue;
            }
            if (y + 18 > bottom) {
                break;
            }
            portalRow(g, y, p, false);
            y += 20;
        }
    }

    /** Turquoise for a lit portal, gold for one that is closed or waiting on a destination, red for a broken frame, a lock or a server that is down. */
    private int portalColour(AdminData.PortalEntry p) {
        return switch (p.status()) {
            case "READY" -> "open".equals(p.doors()) ? TQ : "locked".equals(p.doors()) ? BAD : GOLD;
            case "NO_DESTINATION", "WAITING", "MISSING", "LINK_IN_USE" -> GOLD;
            default -> BAD;
        };
    }

    private void portalRow(GuiGraphics g, int y, AdminData.PortalEntry p, boolean withServer) {
        g.fill(left + 10, y, left + W - 10, y + 18, DIM);
        g.fill(left + 11, y + 1, left + W - 11, y + 17, SLOT);
        g.drawString(font, p.name(), left + 14, y + 2, TEXT, false);
        String state = p.status().toLowerCase().replace('_', ' ') + (p.doors().isEmpty() ? "" : ", " + p.doors());
        small(g, clip((withServer ? p.server() + "  " : "") + p.x() + "," + p.y() + "," + p.z() + "  " + state + "  " + p.destination(), W - 150), left + 14, y + 11, portalColour(p));
        int bx = left + W - 14;
        if (!data.readOnly()) {
            boolean locked = "locked".equals(p.doors());
            bx = button(g, bx, y + 3, Component.translatable(locked ? "screen.bsp_teleport.admin.unlock" : "screen.bsp_teleport.admin.lock").getString(), locked ? BAD : DIM, locked ? BAD : MUTED,
                    () -> send(AdminAction.PORTAL_COMMAND, p.id() + ";" + (locked ? "unlock" : "lock"))) - 2;
            bx = button(g, bx, y + 3, Component.translatable("screen.bsp_teleport.admin.close_now").getString(), DIM, MUTED, () -> send(AdminAction.PORTAL_COMMAND, p.id() + ";close")) - 2;
            bx = button(g, bx, y + 3, Component.translatable("screen.bsp_teleport.admin.open_now").getString(), DIM, OK, () -> send(AdminAction.PORTAL_COMMAND, p.id() + ";open")) - 2;
        }
        button(g, bx, y + 3, Component.translatable("screen.bsp_teleport.admin.go").getString(), TQ, TQ, () -> go(AdminAction.GO_PORTAL, p.id()));
    }

    private record Target(String kind, String name) {
    }

    private List<Target> targets() {
        List<Target> out = new ArrayList<>();
        for (String s : data.restricted()) {
            out.add(new Target("server", s));
        }
        List<String> seen = new ArrayList<>();
        for (AdminData.PortalEntry p : data.portals()) {
            if (!seen.contains(p.name().toLowerCase())) {
                seen.add(p.name().toLowerCase());
                out.add(new Target("portal", p.name()));
            }
        }
        for (AdminData.WarpRow w : data.warps()) {
            out.add(new Target("warp", w.name()));
        }
        for (AdminData.TokenRow t : data.tokens()) {
            if ("region".equals(t.kind()) && out.stream().noneMatch(x -> x.kind().equals("region") && x.name().equalsIgnoreCase(t.target()))) {
                out.add(new Target("region", t.target()));
            }
        }
        return out;
    }

    @Nullable
    private AdminData.TokenRow tokenOf(String kind, String target) {
        for (AdminData.TokenRow t : data.tokens()) {
            if (t.kind().equals(kind) && t.target().equalsIgnoreCase(target)) {
                return t;
            }
        }
        return null;
    }

    private void drawTokens(GuiGraphics g) {
        int y = top + 28;
        small(g, Component.translatable("screen.bsp_teleport.admin.tokens_help").getString(), left + 10, y, MUTED);
        y += 12;
        // Left: the twenty tokens, five across; dim means free, violet assigned, turquoise picked.
        int lx = left + 10;
        small(g, Component.translatable("screen.bsp_teleport.admin.tokens_list").getString().toUpperCase(), lx, y, MUTED);
        int gy = y + 10;
        for (AdminData.TokenRow t : data.tokens()) {
            int i = t.token() - 1;
            int cx = lx + (i % 5) * 19, cy = gy + (i / 5) * 19;
            boolean picked = t.token() == pickedToken, assigned = !t.kind().isEmpty();
            g.fill(cx, cy, cx + 18, cy + 18, picked ? TQ : assigned ? VIO : DIM);
            g.fill(cx + 1, cy + 1, cx + 17, cy + 17, picked ? 0xFF0F2A28 : SLOT);
            g.renderItem(new net.minecraft.world.item.ItemStack(com.mrgregles.bsp_teleport.registry.ModItems.ACCESS_TOKENS[i].get()), cx + 1, cy + 1);
            if (!assigned && !picked) {
                g.fill(cx + 1, cy + 1, cx + 17, cy + 17, 0x90000000);
            }
            int n = t.token();
            hits.add(new Hit(cx, cy, 18, 18, () -> pickedToken = pickedToken == n ? 0 : n));
        }
        int infoY = gy + 4 * 19 + 2;
        AdminData.TokenRow picked = pickedToken > 0 ? data.tokens().get(pickedToken - 1) : null;
        int lw = 118;
        g.fill(lx, infoY, lx + lw, infoY + 46, DIM);
        g.fill(lx + 1, infoY + 1, lx + lw - 1, infoY + 45, SLOT);
        if (picked == null) {
            small(g, Component.translatable("screen.bsp_teleport.admin.token_free").getString(), lx + 4, infoY + 4, MUTED);
        } else {
            small(g, Component.translatable("screen.bsp_teleport.admin.token_picked", com.mrgregles.bsp_teleport.access.TokenAssignment.roman(picked.token())).getString().toUpperCase(), lx + 4, infoY + 4, TQ);
            boolean assigned = !picked.kind().isEmpty();
            small(g, assigned ? clip(Component.translatable("screen.bsp_teleport.admin.kind." + picked.kind()).getString() + " " + picked.target(), lw - 8)
                    : Component.translatable("screen.bsp_teleport.admin.token_free").getString(), lx + 4, infoY + 13, assigned ? VIO : MUTED);
            if (assigned && !data.readOnly()) {
                int n = picked.token();
                int bx = lx + lw - 4;
                bx = button(g, bx, infoY + 22, "+", DIM, GOLD, () -> send(AdminAction.TOKEN_HOURS, n + ";" + (picked.hours() + 24))) - 2;
                String hrs = Component.translatable("screen.bsp_teleport.admin.hours", picked.hours()).getString();
                int tw = (int) (font.width(hrs) * 0.75f) + 6;
                small(g, hrs, bx - tw + 3, infoY + 25, GOLD);
                bx -= tw + 2;
                button(g, bx, infoY + 22, "-", DIM, GOLD, () -> send(AdminAction.TOKEN_HOURS, n + ";" + Math.max(1, picked.hours() - 24)));
                bx = lx + lw - 4;
                bx = button(g, bx, infoY + 34, Component.translatable("screen.bsp_teleport.admin.clear").getString(), BAD, BAD, () -> send(AdminAction.TOKEN_CLEAR, String.valueOf(n))) - 2;
                bx = button(g, bx, infoY + 34, Component.translatable("screen.bsp_teleport.admin.full_stack").getString(), GOLD, GOLD, () -> send(AdminAction.TOKEN_STACK, n + ";full;0")) - 2;
                button(g, bx, infoY + 34, Component.translatable("screen.bsp_teleport.admin.rent_stack").getString(), GOLD, GOLD, () -> send(AdminAction.TOKEN_STACK, n + ";rent;0"));
            }
        }
        // The hint box under the info (left) and the region box at the foot of the targets (right): both act on the picked token.
        int rx = left + 10 + lw + 8, rw = W - 20 - lw - 8;
        textBox.setX(lx);
        textBox.setY(infoY + 48);
        textBox.setWidth(lw - 30);
        textBox.setHint(Component.translatable("screen.bsp_teleport.admin.hint"));
        nameBox.setX(rx);
        nameBox.setY(top + H - 40);
        nameBox.setWidth(rw - 44);
        if (!data.readOnly() && picked != null) {
            int n = picked.token();
            button(g, lx + lw, infoY + 49, Component.translatable("screen.bsp_teleport.admin.set").getString(), TQ, TQ, () -> {
                send(AdminAction.TOKEN_HINT, n + ";" + textBox.getValue().trim());
                textBox.setValue("");
            });
            button(g, rx + rw, top + H - 39, Component.translatable("screen.bsp_teleport.admin.assign_region").getString(), VIO, VIO, () -> {
                if (!nameBox.getValue().trim().isEmpty()) {
                    send(AdminAction.TOKEN_ASSIGN, n + ";region;" + nameBox.getValue().trim());
                    nameBox.setValue("");
                }
            });
        }
        // Right: the targets; click one to place the picked token there.
        small(g, Component.translatable("screen.bsp_teleport.admin.targets").getString().toUpperCase(), rx, y, MUTED);
        int ry = y + 10;
        int bottom = top + H - 44;
        int i = 0;
        for (Target t : targets()) {
            if (i++ < scroll) {
                continue;
            }
            if (ry + 20 > bottom) {
                break;
            }
            AdminData.TokenRow has = tokenOf(t.kind(), t.name());
            g.fill(rx, ry, rx + rw, ry + 18, has != null ? VIO : DIM);
            g.fill(rx + 1, ry + 1, rx + rw - 1, ry + 17, SLOT);
            if (has != null) {
                g.renderItem(new net.minecraft.world.item.ItemStack(com.mrgregles.bsp_teleport.registry.ModItems.ACCESS_TOKENS[has.token() - 1].get()), rx + 2, ry + 1);
            }
            g.drawString(font, clip(t.name(), rw - 50), rx + 22, ry + 2, TEXT, false);
            small(g, Component.translatable("screen.bsp_teleport.admin.kind." + t.kind()).getString() + (has != null ? "  " + com.mrgregles.bsp_teleport.access.TokenAssignment.roman(has.token()) : pickedToken > 0 ? "  " + Component.translatable("screen.bsp_teleport.admin.place").getString() : ""),
                    rx + 22, ry + 11, has != null ? VIO : pickedToken > 0 ? TQ : MUTED);
            if (!data.readOnly() && pickedToken > 0) {
                int n = pickedToken;
                hits.add(new Hit(rx, ry, rw, 18, () -> send(AdminAction.TOKEN_ASSIGN, n + ";" + t.kind() + ";" + t.name())));
            }
            ry += 20;
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
            case PORTALS -> portalMap ? 0 : data.portals().size() + (int) data.portals().stream().map(AdminData.PortalEntry::server).distinct().count();
            case TOKENS -> targets().size();
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
