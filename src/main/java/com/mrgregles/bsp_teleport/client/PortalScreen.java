package com.mrgregles.bsp_teleport.client;

import com.mrgregles.bsp_teleport.ClientConfig;
import com.mrgregles.bsp_teleport.portal.PortalColour;
import com.mrgregles.bsp_teleport.portal.PortalScreenAction;
import com.mrgregles.bsp_teleport.portal.PortalScreenData;
import com.mrgregles.bsp_teleport.portal.PortalStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The Portal Core screen, Tabs layout (D63, D66): a status line, then DESTINATION, LOOK, OPEN/CLOSE and ACCESS. The server decides everything; this screen only shows the data it was sent and sends actions back.
 * Moderators see it read-only.
 */
public class PortalScreen extends Screen {
    /** D128: 440 by 270 GUI units with small text; the scale fills up to 90 % of the window, capped by ui.screenScale. */
    private static final int W = 440, H = 270, TAB_W = 62, COL_W = 200;
    private static final int TQ = BandClient.TQ, BG = BandClient.BG, SLOT = BandClient.SLOT, DIM = BandClient.DIM, MUTED = BandClient.MUTED,
            TEXT = BandClient.TEXT, VIO = BandClient.VIO, GOLD = BandClient.GOLD, BAD = BandClient.BAD, OK = BandClient.OK;

    private enum Tab { DESTINATION, LOOK, DOORS, ACCESS }

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private PortalScreenData data;
    private Tab tab = Tab.DESTINATION;
    /** The destination kind whose picker is open, or empty for the current one. */
    private String pick = "";
    /** The picker row selected on the DESTINATION tab (a warp name, a server, or a portal row id) before ADD. */
    private String pickedTarget = "";
    /** The list entry whose label is being typed, or -1. */
    private int editing = -1;
    /** The mode whose form is open, or empty for the current one. */
    private String modePick = "";
    /** ACCESS tab (D86): the kind of the rule being added, and the item rule's used-up switch. */
    private String ruleKind = "RANK";
    private boolean ruleConsume;
    private int scroll;
    private int left, top;
    private float scale = 1f;
    private final List<Hit> hits = new ArrayList<>();
    private EditBox box1, box2, nameBox;
    private boolean boxesFilled;
    /** The portal name last put in the DESTINATION tab's name box, so a new name from the server replaces it once. */
    private String nameShown;

    public PortalScreen(PortalScreenData data) {
        super(Component.translatable("screen.bsp_teleport.portal.title"));
        this.data = data;
    }

    public static void show(PortalScreenData data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof PortalScreen screen && screen.data.pos().equals(data.pos())) {
            screen.update(data);
        } else {
            mc.setScreen(new PortalScreen(data));
        }
    }

    public void update(PortalScreenData data) {
        this.data = data;
    }

    @Override
    protected void init() {
        float fit = (float) Math.min(width * 0.9 / W, height * 0.9 / H);
        scale = Math.max(0.5f, Math.min(ClientConfig.screenScale(width, height, W, H), fit));
        left = ((int) (width / scale) - W) / 2;
        top = ((int) (height / scale) - H) / 2;
        box1 = new EditBox(font, left + 10, top + 60, 100, 14, Component.literal("a"));
        box1.setMaxLength(64);
        addRenderableWidget(box1);
        box2 = new EditBox(font, left + 10, top + 80, 100, 14, Component.literal("b"));
        box2.setMaxLength(64);
        addRenderableWidget(box2);
        nameBox = new EditBox(font, left + 44, top + 47, 170, 14, Component.literal("name"));
        nameBox.setMaxLength(32);
        addRenderableWidget(nameBox);
        nameShown = null;
        boxesFilled = false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void send(PortalScreenAction action, String arg) {
        ClientNet.sendPortal(data.pos(), action, arg);
    }

    private boolean ro() {
        return data.readOnly();
    }

    private String currentPick() {
        return pick.isEmpty() ? "WARP" : pick;
    }

    private String currentMode() {
        return modePick.isEmpty() ? data.mode() : modePick;
    }

    // Drawing

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1f);
        hits.clear();
        box1.visible = false;
        box2.visible = false;
        nameBox.visible = false;
        g.fill(left - 1, top - 1, left + W + 1, top + H + 1, VIO);
        g.fill(left, top, left + W, top + H, BG);
        g.drawString(font, title.getString().toUpperCase(), left + 10, top + 8, VIO, false);
        small(g, clip(data.name() + ".  " + Component.translatable(ro() ? "screen.bsp_teleport.portal.readonly" : "screen.bsp_teleport.portal.help").getString(), W - 20), left + 10, top + 19, ro() ? GOLD : MUTED);

        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            int tx = left + W - 10 - (tabs.length - i) * (TAB_W + 2);
            boolean on = tabs[i] == tab;
            g.fill(tx, top + 5, tx + TAB_W, top + 18, on ? VIO : DIM);
            g.fill(tx + 1, top + 6, tx + TAB_W - 1, top + 17, SLOT);
            String label = Component.translatable("screen.bsp_teleport.portal.tab." + tabs[i].name().toLowerCase()).getString().toUpperCase();
            small(g, label, tx + (TAB_W - (int) (font.width(label) * 0.75f)) / 2, top + 8, on ? VIO : MUTED);
            Tab t = tabs[i];
            hits.add(new Hit(tx, top + 5, TAB_W, 13, () -> {
                tab = t;
                scroll = 0;
                boxesFilled = false;
            }));
        }
        drawStatus(g);
        switch (tab) {
            case DESTINATION -> drawDestination(g);
            case LOOK -> drawLook(g);
            case DOORS -> drawDoors(g);
            case ACCESS -> drawAccess(g);
        }
        drawFooter(g);
        super.render(g, (int) (mouseX / scale), (int) (mouseY / scale), partial);
        g.pose().popPose();
    }

    private void drawStatus(GuiGraphics g) {
        PortalStatus status;
        try {
            status = PortalStatus.valueOf(data.status());
        } catch (IllegalArgumentException e) {
            status = PortalStatus.NO_FRAME;
        }
        String line = status == PortalStatus.TOO_BIG ? Component.translatable(status.key(), data.maxSize(), data.maxOpening()).getString() : Component.translatable(status.key()).getString();
        if (status == PortalStatus.READY && !data.doors().isEmpty() && !"open".equals(data.doors())) {
            line = line + "  " + Component.translatable("screen.bsp_teleport.portal.doors_now", data.doors()).getString();
        }
        int colour = status == PortalStatus.READY ? OK : status.frameOk() ? GOLD : BAD;
        g.fill(left + 10, top + 28, left + W - 10, top + 41, colour);
        g.fill(left + 11, top + 29, left + W - 11, top + 40, SLOT);
        small(g, clip(line.toUpperCase(), W - 30), left + 14, top + 32, colour);
    }

    /**
     * The DESTINATION tab in the Split layout (D107) at the D128 size, layout A (D127): the Name row, the kind pills, the
     * picker on the left with the picked entry spelled out in full under it, the list on the right (D94), the switches.
     */
    private void drawDestination(GuiGraphics g) {
        int y = top + 46;
        drawNameRow(g, y);
        y += 20;
        String cur = currentPick();
        int px = left + 10;
        for (String kind : new String[]{"WARP", "PORTAL_WARP", "PORTAL", "RANDOM", "POSITION", "BACK"}) {
            boolean on = kind.equals(cur);
            px = pill(g, px, y, Component.translatable("screen.bsp_teleport.portal.kind." + kind.toLowerCase()).getString(), on, () -> {
                pick = kind;
                pickedTarget = "";
                scroll = 0;
            }) + 3;
        }
        y += 16;
        int listX = left + 10 + COL_W + 10;
        int sy = top + H - 58;
        int bottom = sy - 4;
        // Left: the picker for the current kind, the picked entry in full, then Add destination.
        int addY = bottom - 13, detailTop = addY - 24;
        int ly = y;
        switch (cur) {
            case "WARP", "PORTAL_WARP" -> {
                boolean portal = "PORTAL_WARP".equals(cur);
                List<PortalScreenData.WarpEntry> list = data.warps().stream().filter(w -> w.portal() == portal).toList();
                small(g, Component.translatable(list.isEmpty() ? "screen.bsp_teleport.portal.no_warps" : "screen.bsp_teleport.portal.pick_warp").getString(), left + 12, ly, MUTED);
                ly += 10;
                for (int i = scroll; i < list.size() && ly + 12 <= detailTop; i++) {
                    PortalScreenData.WarpEntry w = list.get(i);
                    pickerRow(g, ly, w.name(), w.server(), w.name().equals(pickedTarget), () -> pickedTarget = w.name());
                    ly += 12;
                }
                list.stream().filter(w -> w.name().equals(pickedTarget)).findFirst().ifPresentOrElse(
                        w -> detail(g, detailTop, w.name(), Component.translatable(portal ? "screen.bsp_teleport.portal.detail_portal_warp" : "screen.bsp_teleport.portal.detail_warp", w.server()).getString()),
                        () -> detail(g, detailTop, null, null));
            }
            case "PORTAL" -> {
                small(g, Component.translatable(data.portals().isEmpty() ? "screen.bsp_teleport.portal.no_portals" : "screen.bsp_teleport.portal.pick_portal").getString(), left + 12, ly, MUTED);
                ly += 10;
                for (int i = scroll; i < data.portals().size() && ly + 12 <= detailTop; i++) {
                    PortalScreenData.PortalEntry e = data.portals().get(i);
                    pickerRow(g, ly, e.name(), e.server(), e.id().equals(pickedTarget), () -> pickedTarget = e.id());
                    ly += 12;
                }
                data.portals().stream().filter(e -> e.id().equals(pickedTarget)).findFirst().ifPresentOrElse(
                        e -> detail(g, detailTop, e.name(), where(e.id())),
                        () -> detail(g, detailTop, null, null));
            }
            case "RANDOM" -> {
                small(g, Component.translatable("screen.bsp_teleport.portal.pick_server").getString(), left + 12, ly, MUTED);
                ly += 10;
                for (int i = scroll; i < data.servers().size() && ly + 12 <= detailTop; i++) {
                    String sv = data.servers().get(i);
                    boolean here = sv.equals(data.server());
                    pickerRow(g, ly, sv, here ? Component.translatable("screen.bsp_teleport.admin.this_server").getString() : "", sv.equals(pickedTarget), () -> pickedTarget = sv);
                    ly += 12;
                }
                detail(g, detailTop, pickedTarget.isEmpty() ? null : pickedTarget, pickedTarget.isEmpty() ? null : Component.translatable("screen.bsp_teleport.portal.detail_random").getString());
            }
            case "POSITION" -> {
                pickRow(g, ly, COL_W, false);
                small(g, clip(Component.translatable("screen.bsp_teleport.portal.use_here_help").getString(), COL_W - 8), left + 14, ly + 2, MUTED);
            }
            case "BACK" -> {
                // D110, D126 A: nothing to pick, one line of explanation and Add destination.
                pickRow(g, ly, COL_W, false);
                small(g, clip(Component.translatable("screen.bsp_teleport.portal.back_help1").getString(), COL_W - 8), left + 14, ly + 2, MUTED);
                ly += 12;
                pickRow(g, ly, COL_W, false);
                small(g, clip(Component.translatable("screen.bsp_teleport.portal.back_help2").getString(), COL_W - 8), left + 14, ly + 2, MUTED);
            }
            default -> {
            }
        }
        if (!ro()) {
            boolean free = "POSITION".equals(cur) || "BACK".equals(cur);
            boolean can = (free || !pickedTarget.isEmpty()) && data.dests().size() < data.maxDests();
            String add = Component.translatable("screen.bsp_teleport.portal.add").getString();
            button(g, left + 10 + COL_W, addY, add, can ? TQ : DIM, can ? TQ : MUTED, can ? () -> {
                send(PortalScreenAction.DEST_ADD, ("POSITION".equals(cur) ? "POSITION;" : cur + ";" + pickedTarget));
                pickedTarget = "";
            } : null);
        }
        // Right: the list.
        int ry = y;
        small(g, Component.translatable("screen.bsp_teleport.portal.dests", data.dests().size(), data.maxDests()).getString().toUpperCase(), listX + 2, ry, MUTED);
        ry += 10;
        int listW = left + W - 10 - listX;
        if (data.dests().isEmpty()) {
            small(g, clip(Component.translatable("screen.bsp_teleport.portal.dests_help").getString(), listW - 4), listX + 2, ry, DIM);
        }
        for (int i = 0; i < data.dests().size(); i++) {
            PortalScreenData.DestEntry d = data.dests().get(i);
            if (ry + 11 > bottom) {
                break;
            }
            boolean up = "READY".equals(d.status());
            g.fill(listX, ry, listX + listW, ry + 11, up ? DIM : BAD);
            g.fill(listX + 1, ry + 1, listX + listW - 1, ry + 10, SLOT);
            small(g, (i + 1) + ".", listX + 3, ry + 2, GOLD);
            int bx = listX + listW - 2;
            if (!ro()) {
                int idx = i;
                bx = button(g, bx, ry, Component.translatable("screen.bsp_teleport.portal.del").getString(), BAD, BAD, () -> send(PortalScreenAction.DEST_DEL, String.valueOf(idx))) - 2;
                if (i > 0) {
                    bx = button(g, bx, ry, Component.translatable("screen.bsp_teleport.portal.up").getString(), DIM, MUTED, () -> send(PortalScreenAction.DEST_UP, String.valueOf(idx))) - 2;
                }
            }
            if (editing == i && !ro()) {
                String set = Component.translatable("screen.bsp_teleport.portal.set").getString();
                int setW = (int) (font.width(set) * 0.75f) + 8;
                box1.setX(listX + 14);
                box1.setY(ry - 1);
                box1.setWidth(Math.max(30, bx - setW - 4 - listX - 14));
                box1.visible = true;
                int idx = i;
                button(g, bx, ry, set, TQ, TQ, () -> {
                    send(PortalScreenAction.DEST_LABEL, idx + ";" + box1.getValue().trim());
                    editing = -1;
                    box1.visible = false;
                });
            } else {
                small(g, clip(d.shown(), bx - listX - 18), listX + 14, ry + 2, up ? TEXT : MUTED);
                if (!ro()) {
                    int idx = i;
                    hits.add(new Hit(listX + 12, ry, Math.max(10, bx - listX - 12), 11, () -> {
                        editing = idx;
                        box1.setValue(d.label());
                        box1.setSuggestion(d.label().isEmpty() ? d.shown() : "");
                        box1.visible = true;
                        setFocused(box1);
                    }));
                }
            }
            ry += 12;
        }
        if (!data.dests().isEmpty() && !ro() && ry + 10 <= bottom) {
            small(g, clip(Component.translatable("screen.bsp_teleport.portal.label_hint").getString(), listW - 4), listX + 2, ry + 1, DIM);
        }
        if (editing < 0 || tab != Tab.DESTINATION) {
            box1.visible = false;
        }
        switchRow(g, sy, Component.translatable("screen.bsp_teleport.portal.arrive_only").getString(), Component.translatable("screen.bsp_teleport.portal.arrive_only_help").getString(),
                data.arriveOnly(), v -> send(PortalScreenAction.ARRIVE_ONLY, String.valueOf(v)));
        switchRow(g, sy + 18, Component.translatable("screen.bsp_teleport.portal.close_when_down").getString(), Component.translatable("screen.bsp_teleport.portal.close_when_down_help").getString(),
                data.closeWhenDown(), v -> send(PortalScreenAction.CLOSE_WHEN_DOWN, String.valueOf(v)));
    }

    /** D127: the portal's name on the DESTINATION tab, where other portals' pickers and /bsptp portal <name> find it. */
    private void drawNameRow(GuiGraphics g, int y) {
        row(g, y, 16, DIM);
        small(g, Component.translatable("screen.bsp_teleport.portal.name").getString(), left + 14, y + 5, TEXT);
        if (ro()) {
            small(g, clip(data.name(), W - 70), left + 44, y + 5, GOLD);
            return;
        }
        if (nameShown == null || !nameShown.equals(data.name())) {
            nameShown = data.name();
            if (!nameBox.isFocused()) {
                nameBox.setValue(data.name());
            }
        }
        nameBox.setX(left + 44);
        nameBox.setY(y + 1);
        nameBox.setWidth(170);
        nameBox.visible = true;
        String rename = Component.translatable("screen.bsp_teleport.portal.rename").getString();
        int bx = button(g, left + W - 14, y + 2, rename, TQ, TQ, () -> send(PortalScreenAction.NAME, nameBox.getValue().trim()));
        small(g, clip(Component.translatable("screen.bsp_teleport.portal.name_help_short").getString(), bx - (left + 222)), left + 222, y + 5, MUTED);
    }

    /** One picker row: the name on the left, the server (violet when another) on the right, each cut to its space. */
    private void pickerRow(GuiGraphics g, int y, String name, String server, boolean selected, Runnable pickIt) {
        pickRow(g, y, COL_W, selected);
        String sv = clip(server, 80);
        int svW = (int) (font.width(sv) * 0.75f);
        small(g, clip(name, COL_W - 12 - svW - 6), left + 14, y + 2, selected ? TQ : TEXT);
        small(g, sv, left + 10 + COL_W - 4 - svW, y + 2, server.equals(data.server()) || server.isEmpty() ? MUTED : VIO);
        if (!ro()) {
            hits.add(new Hit(left + 10, y, COL_W, 11, pickIt));
        }
    }

    /** D127: the picked entry spelled out in full under the picker (two lines), or a hint while nothing is picked. */
    private void detail(GuiGraphics g, int y, @Nullable String name, @Nullable String where) {
        g.fill(left + 10, y, left + 10 + COL_W, y + 21, name == null ? DIM : TQ);
        g.fill(left + 11, y + 1, left + 10 + COL_W - 1, y + 20, SLOT);
        if (name == null) {
            small(g, clip(Component.translatable("screen.bsp_teleport.portal.detail_hint").getString(), COL_W - 8), left + 14, y + 7, DIM);
            return;
        }
        small(g, clip(name, COL_W - 8), left + 14, y + 3, TQ);
        small(g, clip(where == null ? "" : where, COL_W - 8), left + 14, y + 12, MUTED);
    }

    /** "BSP-SafeRoom  minecraft:overworld  4, 65, -90" from a portal row id ({@code server|world|x,y,z}). */
    private static String where(String rowId) {
        String[] p = rowId.split("\\|", 3);
        return p.length < 3 ? rowId : p[0] + "  " + p[1] + "  " + p[2].replace(",", ", ");
    }

    /** A picker row in the left column of the DESTINATION tab. */
    private void pickRow(GuiGraphics g, int y, int w, boolean selected) {
        g.fill(left + 10, y, left + 10 + w, y + 11, selected ? TQ : DIM);
        g.fill(left + 11, y + 1, left + 10 + w - 1, y + 10, SLOT);
    }

    private void drawLook(GuiGraphics g) {
        int y = top + 46;
        // Colour
        row(g, y, 30, DIM);
        small(g, Component.translatable("screen.bsp_teleport.portal.colour", data.colour()).getString(), left + 14, y + 3, TEXT);
        PortalColour[] colours = PortalColour.values();
        for (int i = 0; i < colours.length; i++) {
            int cx = left + 14 + (i % 12) * 11, cy = y + 12 + (i / 12) * 9;
            boolean on = colours[i].getSerializedName().equals(data.colour());
            g.fill(cx, cy, cx + 9, cy + 7, on ? TEXT : DIM);
            g.fill(cx + 1, cy + 1, cx + 8, cy + 6, 0xFF000000 | colours[i].tint());
            PortalColour c = colours[i];
            if (!ro()) {
                hits.add(new Hit(cx, cy, 9, 7, () -> send(PortalScreenAction.COLOUR, c.getSerializedName())));
            }
        }
        y += 34;
        // Doors
        row(g, y, 16, DIM);
        small(g, Component.translatable("screen.bsp_teleport.portal.doors_style").getString(), left + 14, y + 2, TEXT);
        small(g, clip(Component.translatable("screen.bsp_teleport.portal.doors_help").getString(), W - 130), left + 14, y + 9, MUTED);
        int bx = left + W - 14;
        bx = button(g, bx, y + 2, Component.translatable("screen.bsp_teleport.portal.rendered").getString(), "RENDERED".equals(data.doorStyle()) ? VIO : DIM, "RENDERED".equals(data.doorStyle()) ? VIO : MUTED,
                ro() ? null : () -> send(PortalScreenAction.DOORS, "rendered")) - 2;
        button(g, bx, y + 2, Component.translatable("screen.bsp_teleport.portal.blocks").getString(), "BLOCKS".equals(data.doorStyle()) ? VIO : DIM, "BLOCKS".equals(data.doorStyle()) ? VIO : MUTED,
                ro() ? null : () -> send(PortalScreenAction.DOORS, "blocks"));
        y += 20;
        // Facing
        row(g, y, 16, DIM);
        small(g, Component.translatable("screen.bsp_teleport.portal.facing", data.facing()).getString(), left + 14, y + 2, TEXT);
        small(g, clip(Component.translatable("screen.bsp_teleport.portal.facing_help").getString(), W - 80), left + 14, y + 9, MUTED);
        if (!ro()) {
            button(g, left + W - 14, y + 2, Component.translatable("screen.bsp_teleport.portal.flip").getString(), TQ, TQ, () -> send(PortalScreenAction.FLIP, ""));
        }
        y += 20;
        if (!boxesFilled) {
            box2.setValue(data.arrivalLine());
            boxesFilled = true;
        }
        // The name is set on the DESTINATION tab only (D127, D132).
        // Arrival line
        row(g, y, 20, DIM);
        small(g, Component.translatable("screen.bsp_teleport.portal.arrival_line").getString(), left + 14, y + 2, TEXT);
        small(g, clip(Component.translatable("screen.bsp_teleport.portal.arrival_help").getString(), W - 240), left + 14, y + 10, MUTED);
        textRow(g, y, box2, PortalScreenAction.ARRIVAL_LINE);
    }

    /** An edit box with a Set button on the right of a row. */
    private void textRow(GuiGraphics g, int y, EditBox box, PortalScreenAction action) {
        if (ro()) {
            small(g, clip(box.getValue().isEmpty() ? "-" : box.getValue(), 180), left + W - 200, y + 6, GOLD);
            return;
        }
        box.setX(left + W - 222);
        box.setY(y + 3);
        box.setWidth(176);
        box.visible = true;
        button(g, left + W - 14, y + 4, Component.translatable("screen.bsp_teleport.portal.set").getString(), TQ, TQ, () -> send(action, box.getValue().trim()));
    }

    private void drawDoors(GuiGraphics g) {
        int y = top + 46;
        String cur = currentMode();
        int px = left + 10;
        for (String m : new String[]{"MANUAL", "REDSTONE", "REAL_CLOCK", "GAME_CLOCK"}) {
            boolean on = m.equals(cur);
            px = pill(g, px, y, Component.translatable("screen.bsp_teleport.portal.mode." + m.toLowerCase()).getString(), on, () -> {
                modePick = m;
                boxesFilled = false;
                if (!ro() && !m.equals(data.mode())) {
                    if (m.equals("MANUAL")) {
                        send(PortalScreenAction.MODE_MANUAL, "");
                    } else if (m.equals("REDSTONE")) {
                        send(PortalScreenAction.MODE_REDSTONE, String.valueOf(data.openWhenPowered()));
                    }
                }
            }) + 3;
        }
        y += 18;
        switch (cur) {
            case "MANUAL" -> {
                row(g, y, 16, DIM);
                small(g, clip(Component.translatable("screen.bsp_teleport.portal.manual_help").getString(), W - 150), left + 14, y + 5, MUTED);
                if (!ro()) {
                    int bx = left + W - 14;
                    bx = button(g, bx, y + 2, Component.translatable("screen.bsp_teleport.portal.close_now").getString(), BAD, BAD, () -> send(PortalScreenAction.CLOSE_NOW, "")) - 2;
                    button(g, bx, y + 2, Component.translatable("screen.bsp_teleport.portal.open_now").getString(), OK, OK, () -> send(PortalScreenAction.OPEN_NOW, ""));
                }
            }
            case "REDSTONE" -> {
                row(g, y, 16, DIM);
                small(g, clip(Component.translatable("screen.bsp_teleport.portal.redstone_help").getString(), W - 150), left + 14, y + 2, MUTED);
                small(g, Component.translatable(data.powered() ? "screen.bsp_teleport.portal.powered" : "screen.bsp_teleport.portal.unpowered").getString(), left + 14, y + 9, data.powered() ? OK : MUTED);
                button(g, left + W - 14, y + 2, Component.translatable(data.openWhenPowered() ? "screen.bsp_teleport.portal.open_when_powered" : "screen.bsp_teleport.portal.closed_when_powered").getString(),
                        VIO, VIO, ro() ? null : () -> send(PortalScreenAction.MODE_REDSTONE, String.valueOf(!data.openWhenPowered())));
            }
            case "REAL_CLOCK", "GAME_CLOCK" -> {
                boolean real = cur.equals("REAL_CLOCK");
                if (!boxesFilled) {
                    box1.setValue(real ? hhmm(data.openAt()) : String.valueOf(data.gameOpenAt()));
                    box2.setValue(real ? hhmm(data.closeAt()) : String.valueOf(data.gameCloseAt()));
                    boxesFilled = true;
                }
                row(g, y, 22, DIM);
                small(g, clip(real ? Component.translatable("screen.bsp_teleport.portal.real_help", data.zone()).getString() : Component.translatable("screen.bsp_teleport.portal.game_help").getString(), W - 60), left + 14, y + 2, MUTED);
                if (ro()) {
                    small(g, box1.getValue() + " " + Component.translatable("screen.bsp_teleport.portal.to").getString() + " " + box2.getValue(), left + 14, y + 11, GOLD);
                } else {
                    box1.setX(left + 14);
                    box1.setY(y + 9);
                    box1.setWidth(44);
                    box1.visible = true;
                    small(g, Component.translatable("screen.bsp_teleport.portal.to").getString(), left + 64, y + 12, MUTED);
                    box2.setX(left + 78);
                    box2.setY(y + 9);
                    box2.setWidth(44);
                    box2.visible = true;
                    button(g, left + W - 14, y + 8, Component.translatable("screen.bsp_teleport.portal.set").getString(), TQ, TQ,
                            () -> send(real ? PortalScreenAction.MODE_REAL : PortalScreenAction.MODE_GAME, box1.getValue().trim() + ";" + box2.getValue().trim()));
                }
                y += 6;
            }
            default -> {
            }
        }
        y += 22;
        switchRow(g, y, Component.translatable("screen.bsp_teleport.portal.lock").getString(), Component.translatable("screen.bsp_teleport.portal.lock_help").getString(),
                data.locked(), v -> send(PortalScreenAction.LOCK, String.valueOf(v)));
        y += 18;
        row(g, y, 16, DIM);
        small(g, Component.translatable("screen.bsp_teleport.portal.cooldown").getString(), left + 14, y + 2, TEXT);
        small(g, clip(Component.translatable("screen.bsp_teleport.portal.cooldown_help").getString(), W - 100), left + 14, y + 9, MUTED);
        int bx = left + W - 14;
        bx = button(g, bx, y + 2, "+", DIM, TQ, ro() ? null : () -> send(PortalScreenAction.COOLDOWN, String.valueOf(data.cooldown() + 1))) - 2;
        String v = data.cooldown() + " s";
        int tw = (int) (font.width(v) * 0.75f) + 6;
        small(g, v, bx - tw + 3, y + 5, GOLD);
        bx -= tw + 2;
        button(g, bx, y + 2, "-", DIM, TQ, ro() ? null : () -> send(PortalScreenAction.COOLDOWN, String.valueOf(Math.max(0, data.cooldown() - 1))));
        y += 18;
        // D118: the warm-up, the same - / + keys as the cooldown, 0 to 30 s.
        row(g, y, 16, data.warmup() > 0 ? TQ : DIM);
        small(g, Component.translatable("screen.bsp_teleport.portal.warmup").getString(), left + 14, y + 2, TEXT);
        small(g, clip(Component.translatable("screen.bsp_teleport.portal.warmup_help").getString(), W - 100), left + 14, y + 9, MUTED);
        bx = left + W - 14;
        bx = button(g, bx, y + 2, "+", DIM, TQ, ro() ? null : () -> send(PortalScreenAction.WARMUP, String.valueOf(Math.min(30, data.warmup() + 1)))) - 2;
        String wv = data.warmup() + " s";
        int ww = (int) (font.width(wv) * 0.75f) + 6;
        small(g, wv, bx - ww + 3, y + 5, GOLD);
        bx -= ww + 2;
        button(g, bx, y + 2, "-", DIM, TQ, ro() ? null : () -> send(PortalScreenAction.WARMUP, String.valueOf(Math.max(0, data.warmup() - 1))));
        y += 20;
        small(g, clip(Component.translatable("screen.bsp_teleport.portal.doors_now", data.doors().isEmpty() ? "-" : data.doors()).getString(), W - 24), left + 12, y + 2, MUTED);
    }

    private void drawAccess(GuiGraphics g) {
        int y = top + 46;
        small(g, clip(Component.translatable("screen.bsp_teleport.portal.rules_help").getString(), W - 24), left + 12, y, MUTED);
        y += 10;
        List<com.mrgregles.bsp_teleport.access.AccessRule> rules = com.mrgregles.bsp_teleport.access.AccessRule.parseList(data.rules());
        if (rules.isEmpty()) {
            small(g, Component.translatable("screen.bsp_teleport.portal.rules_none").getString(), left + 12, y + 2, DIM);
            y += 14;
        }
        for (int i = 0; i < rules.size(); i++) {
            if (y + 14 > top + H - 60) {
                // D131: more rules than fit; the rest stay on the server and show in /bsptp portal <name> info.
                small(g, "+" + (rules.size() - i), left + 14, y + 3, MUTED);
                y += 14;
                break;
            }
            com.mrgregles.bsp_teleport.access.AccessRule r = rules.get(i);
            row(g, y, 12, DIM);
            small(g, clip(r.describe(), W - 70), left + 14, y + 3, TEXT);
            if (!ro()) {
                int index = i;
                button(g, left + W - 14, y, Component.translatable("screen.bsp_teleport.admin.del").getString(), BAD, BAD, () -> send(PortalScreenAction.RULE_DEL, String.valueOf(index)));
            }
            y += 14;
        }
        if (ro()) {
            return;
        }
        y += 4;
        int px = left + 10;
        for (String kind : new String[]{"RANK", "ITEM", "NODE", "COINS", "TOKEN"}) {
            boolean on = kind.equals(ruleKind);
            px = pill(g, px, y, Component.translatable("screen.bsp_teleport.portal.rule." + kind.toLowerCase()).getString(), on, () -> {
                ruleKind = kind;
                boxesFilled = false;
            }) + 3;
        }
        y += 16;
        if (!boxesFilled) {
            box1.setValue("");
            box2.setValue("");
            boxesFilled = true;
        }
        switch (ruleKind) {
            case "RANK", "NODE" -> {
                box1.setX(left + 12);
                box1.setY(y);
                box1.setWidth(150);
                box1.visible = true;
                box1.setHint(Component.translatable("screen.bsp_teleport.portal.rule_value"));
            }
            case "ITEM" -> {
                box1.setX(left + 12);
                box1.setY(y);
                box1.setWidth(120);
                box1.visible = true;
                box1.setHint(Component.translatable("screen.bsp_teleport.portal.rule_value"));
                box2.setX(left + 136);
                box2.setY(y);
                box2.setWidth(36);
                box2.visible = true;
                box2.setHint(Component.translatable("screen.bsp_teleport.portal.rule_count"));
                button(g, left + 230, y + 1, Component.translatable("screen.bsp_teleport.portal.rule_consume").getString(), ruleConsume ? OK : DIM, ruleConsume ? OK : MUTED, () -> ruleConsume = !ruleConsume);
            }
            case "COINS" -> {
                box2.setX(left + 12);
                box2.setY(y);
                box2.setWidth(60);
                box2.visible = true;
                box2.setHint(Component.translatable("screen.bsp_teleport.portal.rule_count"));
            }
            default -> small(g, clip(data.accessToken() > 0 ? Component.translatable("screen.bsp_teleport.portal.rule_token_hint", "Access Token " + com.mrgregles.bsp_teleport.access.TokenAssignment.roman(data.accessToken())).getString()
                    : Component.translatable("screen.bsp_teleport.portal.rule_token_none").getString(), W - 90), left + 12, y + 3, data.accessToken() > 0 ? VIO : GOLD);
        }
        button(g, left + W - 14, y + 1, Component.translatable("screen.bsp_teleport.portal.rule_add").getString(), TQ, TQ, () -> {
            send(PortalScreenAction.RULE_ADD, ruleKind + ";" + box1.getValue().trim() + ";" + box2.getValue().trim() + ";" + ruleConsume);
            boxesFilled = false;
        });
    }

    private void drawFooter(GuiGraphics g) {
        int y = top + H - 20;
        g.fill(left + 10, y - 2, left + W - 10, y - 1, DIM);
        small(g, clip(data.server() + "  " + data.world() + "  " + data.pos().getX() + ", " + data.pos().getY() + ", " + data.pos().getZ(), W - 20), left + 10, y + 2, MUTED);
        String frame = data.width() > 0 ? Component.translatable("screen.bsp_teleport.portal.frame", data.width(), data.height(), data.cells()).getString()
                : Component.translatable("screen.bsp_teleport.portal.no_frame").getString();
        small(g, clip(frame, W - 60), left + 10, y + 10, MUTED);
        String lit = data.lit() ? "LIT" : "DARK";
        small(g, lit, left + W - 10 - (int) (font.width(lit) * 0.75f), y + 10, data.lit() ? OK : MUTED);
    }

    // Pieces

    private void row(GuiGraphics g, int y, int h, int border) {
        g.fill(left + 10, y, left + W - 10, y + h, border);
        g.fill(left + 11, y + 1, left + W - 11, y + h - 1, SLOT);
    }

    private interface BoolAction {
        void run(boolean value);
    }

    private void switchRow(GuiGraphics g, int y, String label, String help, boolean on, BoolAction action) {
        row(g, y, 16, DIM);
        small(g, clip(label, W - 70), left + 14, y + 2, TEXT);
        small(g, clip(help, W - 70), left + 14, y + 9, MUTED);
        button(g, left + W - 14, y + 2, Component.translatable(on ? "screen.bsp_teleport.portal.on" : "screen.bsp_teleport.portal.off").getString(), on ? OK : DIM, on ? OK : MUTED,
                ro() ? null : () -> action.run(!on));
    }

    /** A left-aligned pill; returns its right edge. */
    private int pill(GuiGraphics g, int x, int y, String label, boolean on, Runnable action) {
        int w = (int) (font.width(label) * 0.75f) + 8;
        g.fill(x, y, x + w, y + 12, on ? VIO : DIM);
        g.fill(x + 1, y + 1, x + w - 1, y + 11, SLOT);
        small(g, label.toUpperCase(), x + 4, y + 3, on ? VIO : MUTED);
        hits.add(new Hit(x, y, w, 12, action));
        return x + w;
    }

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

    private String clip(String text, int maxWidth) {
        if (font.width(text) * 0.75f <= maxWidth) {
            return text;
        }
        while (text.length() > 3 && font.width(text + "...") * 0.75f > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "...";
    }

    private static String hhmm(int minute) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
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
        if (tab != Tab.DESTINATION) {
            return super.mouseScrolled(mx / scale, my / scale, delta);
        }
        int count = switch (currentPick()) {
            case "WARP" -> (int) data.warps().stream().filter(w -> !w.portal()).count();
            case "PORTAL_WARP" -> (int) data.warps().stream().filter(PortalScreenData.WarpEntry::portal).count();
            case "PORTAL" -> data.portals().size();
            case "RANDOM" -> data.servers().size();
            default -> 0;
        };
        scroll = Math.max(0, Math.min(Math.max(0, count - 1), scroll - (int) Math.signum(delta)));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (!box1.isFocused() && !box2.isFocused() && !nameBox.isFocused() && minecraft.options.keyInventory.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}
