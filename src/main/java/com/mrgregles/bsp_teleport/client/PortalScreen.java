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
 * The Portal Core screen, Tabs layout (D63, D66): a status line, then DESTINATION, LOOK, OPEN/CLOSE and a greyed
 * ACCESS tab. The server decides everything; this screen only shows the data it was sent and sends actions back.
 * Moderators see it read-only.
 */
public class PortalScreen extends Screen {
    private static final int W = 300, H = 214, TAB_W = 56;
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
    /** The mode whose form is open, or empty for the current one. */
    private String modePick = "";
    private int scroll;
    private int left, top;
    private float scale = 1f;
    private final List<Hit> hits = new ArrayList<>();
    private EditBox box1, box2;
    private boolean boxesFilled;

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
        scale = ClientConfig.screenScale(width, height, W, H);
        left = ((int) (width / scale) - W) / 2;
        top = ((int) (height / scale) - H) / 2;
        box1 = new EditBox(font, left + 10, top + 60, 100, 14, Component.literal("a"));
        box1.setMaxLength(64);
        addRenderableWidget(box1);
        box2 = new EditBox(font, left + 10, top + 80, 100, 14, Component.literal("b"));
        box2.setMaxLength(64);
        addRenderableWidget(box2);
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
        return pick.isEmpty() ? data.destKind() : pick;
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
        g.fill(left - 1, top - 1, left + W + 1, top + H + 1, VIO);
        g.fill(left, top, left + W, top + H, BG);
        g.drawString(font, title.getString().toUpperCase(), left + 10, top + 8, VIO, false);
        small(g, data.name() + ".  " + Component.translatable(ro() ? "screen.bsp_teleport.portal.readonly" : "screen.bsp_teleport.portal.help").getString(), left + 10, top + 19, ro() ? GOLD : MUTED);

        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            int tx = left + W - 10 - (tabs.length - i) * (TAB_W + 2);
            boolean on = tabs[i] == tab, later = tabs[i] == Tab.ACCESS;
            g.fill(tx, top + 5, tx + TAB_W, top + 18, on ? VIO : DIM);
            g.fill(tx + 1, top + 6, tx + TAB_W - 1, top + 17, SLOT);
            String label = Component.translatable("screen.bsp_teleport.portal.tab." + tabs[i].name().toLowerCase()).getString().toUpperCase();
            small(g, label, tx + (TAB_W - (int) (font.width(label) * 0.75f)) / 2, top + 8, on ? VIO : later ? DIM : MUTED);
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

    private void drawDestination(GuiGraphics g) {
        int y = top + 46;
        String cur = currentPick();
        int px = left + 10;
        for (String kind : new String[]{"WARP", "PORTAL_WARP", "RANDOM", "POSITION", "LINK"}) {
            boolean on = kind.equals(cur);
            px = pill(g, px, y, Component.translatable("screen.bsp_teleport.portal.kind." + kind.toLowerCase()).getString(), on, () -> {
                pick = kind;
                scroll = 0;
                boxesFilled = false;
            }) + 3;
        }
        if (!ro() && !"NONE".equals(data.destKind())) {
            button(g, left + W - 10, y, Component.translatable("screen.bsp_teleport.portal.clear").getString(), BAD, BAD, () -> {
                send(PortalScreenAction.DEST_NONE, "");
                pick = "";
            });
        }
        y += 18;
        int bottom = top + H - 64;
        switch (cur) {
            case "WARP", "PORTAL_WARP" -> {
                boolean portal = "PORTAL_WARP".equals(cur);
                List<PortalScreenData.WarpEntry> list = data.warps().stream().filter(w -> w.portal() == portal).toList();
                small(g, Component.translatable(list.isEmpty() ? "screen.bsp_teleport.portal.no_warps" : "screen.bsp_teleport.portal.pick_warp").getString(), left + 12, y, MUTED);
                y += 10;
                int i = 0;
                for (PortalScreenData.WarpEntry w : list) {
                    if (i++ < scroll) {
                        continue;
                    }
                    if (y + 14 > bottom) {
                        break;
                    }
                    boolean sel = w.name().equals(data.destTarget()) && cur.equals(data.destKind());
                    row(g, y, 12, sel ? TQ : DIM);
                    small(g, w.name(), left + 14, y + 3, sel ? TQ : TEXT);
                    small(g, w.server(), left + 120, y + 3, w.server().equals(data.server()) ? MUTED : VIO);
                    if (!ro()) {
                        hits.add(new Hit(left + 10, y, W - 20, 12, () -> send(PortalScreenAction.DEST_WARP, w.name())));
                    }
                    y += 14;
                }
            }
            case "RANDOM" -> {
                small(g, Component.translatable("screen.bsp_teleport.portal.pick_server").getString(), left + 12, y, MUTED);
                y += 10;
                int i = 0;
                for (String s : data.servers()) {
                    if (i++ < scroll) {
                        continue;
                    }
                    if (y + 14 > bottom) {
                        break;
                    }
                    boolean here = s.equals(data.server());
                    boolean sel = "RANDOM".equals(data.destKind()) && (here ? data.destTarget().isEmpty() || data.destTarget().equals(s) : data.destTarget().equals(s));
                    row(g, y, 12, sel ? TQ : DIM);
                    small(g, s + (here ? "  (" + Component.translatable("screen.bsp_teleport.admin.this_server").getString() + ")" : ""), left + 14, y + 3, sel ? TQ : here ? GOLD : TEXT);
                    if (!ro()) {
                        hits.add(new Hit(left + 10, y, W - 20, 12, () -> send(PortalScreenAction.DEST_RANDOM, here ? "" : s)));
                    }
                    y += 14;
                }
            }
            case "POSITION" -> {
                row(g, y, 22, DIM);
                small(g, "POSITION".equals(data.destKind()) ? Component.translatable("screen.bsp_teleport.portal.position_is", data.destText().replace("position ", "")).getString()
                        : Component.translatable("screen.bsp_teleport.portal.kind.none").getString(), left + 14, y + 4, TEXT);
                if (!ro()) {
                    button(g, left + W - 14, y + 5, Component.translatable("screen.bsp_teleport.portal.use_here").getString(), GOLD, GOLD, () -> send(PortalScreenAction.DEST_HERE, ""));
                }
                y += 26;
            }
            case "LINK" -> {
                if (!boxesFilled) {
                    box1.setValue("LINK".equals(data.destKind()) ? data.destTarget() : "");
                    boxesFilled = true;
                }
                small(g, Component.translatable("screen.bsp_teleport.portal.link_id").getString(), left + 12, y, MUTED);
                small(g, Component.translatable("screen.bsp_teleport.portal.link_hint").getString(), left + 60, y, DIM);
                y += 10;
                box1.setX(left + 12);
                box1.setY(y);
                box1.setWidth(140);
                box1.visible = !ro();
                if (!ro()) {
                    button(g, left + 12 + 140 + 36, y + 1, Component.translatable("screen.bsp_teleport.portal.set").getString(), TQ, TQ, () -> send(PortalScreenAction.DEST_LINK, box1.getValue().trim()));
                } else {
                    small(g, data.destTarget(), left + 14, y + 3, TEXT);
                }
                y += 18;
                if ("LINK".equals(data.destKind())) {
                    small(g, data.partner().isEmpty() ? Component.translatable("screen.bsp_teleport.portal.no_partner").getString()
                            : Component.translatable("screen.bsp_teleport.portal.partner", data.partner()).getString(), left + 12, y, data.partner().isEmpty() ? GOLD : OK);
                    y += 10;
                }
            }
            default -> {
                small(g, Component.translatable("message.bsp_teleport.portal.status.no_destination").getString(), left + 12, y, MUTED);
            }
        }
        int sy = top + H - 60;
        switchRow(g, sy, Component.translatable("screen.bsp_teleport.portal.arrive_only").getString(), Component.translatable("screen.bsp_teleport.portal.arrive_only_help").getString(),
                data.arriveOnly(), v -> send(PortalScreenAction.ARRIVE_ONLY, String.valueOf(v)));
        switchRow(g, sy + 18, Component.translatable("screen.bsp_teleport.portal.close_when_down").getString(), Component.translatable("screen.bsp_teleport.portal.close_when_down_help").getString(),
                data.closeWhenDown(), v -> send(PortalScreenAction.CLOSE_WHEN_DOWN, String.valueOf(v)));
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
        small(g, Component.translatable("screen.bsp_teleport.portal.doors_help").getString(), left + 14, y + 9, MUTED);
        int bx = left + W - 14;
        bx = button(g, bx, y + 2, Component.translatable("screen.bsp_teleport.portal.rendered").getString(), "RENDERED".equals(data.doorStyle()) ? VIO : DIM, "RENDERED".equals(data.doorStyle()) ? VIO : MUTED,
                ro() ? null : () -> send(PortalScreenAction.DOORS, "rendered")) - 2;
        button(g, bx, y + 2, Component.translatable("screen.bsp_teleport.portal.blocks").getString(), "BLOCKS".equals(data.doorStyle()) ? VIO : DIM, "BLOCKS".equals(data.doorStyle()) ? VIO : MUTED,
                ro() ? null : () -> send(PortalScreenAction.DOORS, "blocks"));
        y += 20;
        // Facing
        row(g, y, 16, DIM);
        small(g, Component.translatable("screen.bsp_teleport.portal.facing", data.facing()).getString(), left + 14, y + 2, TEXT);
        small(g, Component.translatable("screen.bsp_teleport.portal.facing_help").getString(), left + 14, y + 9, MUTED);
        if (!ro()) {
            button(g, left + W - 14, y + 2, Component.translatable("screen.bsp_teleport.portal.flip").getString(), TQ, TQ, () -> send(PortalScreenAction.FLIP, ""));
        }
        y += 20;
        if (!boxesFilled) {
            box1.setValue(data.name());
            box2.setValue(data.arrivalLine());
            boxesFilled = true;
        }
        // Name
        row(g, y, 20, DIM);
        small(g, Component.translatable("screen.bsp_teleport.portal.name").getString(), left + 14, y + 2, TEXT);
        small(g, Component.translatable("screen.bsp_teleport.portal.name_help").getString(), left + 14, y + 10, MUTED);
        textRow(g, y, box1, PortalScreenAction.NAME);
        y += 24;
        // Arrival line
        row(g, y, 20, DIM);
        small(g, Component.translatable("screen.bsp_teleport.portal.arrival_line").getString(), left + 14, y + 2, TEXT);
        small(g, Component.translatable("screen.bsp_teleport.portal.arrival_help").getString(), left + 14, y + 10, MUTED);
        textRow(g, y, box2, PortalScreenAction.ARRIVAL_LINE);
    }

    /** An edit box with a Set button on the right of a row. */
    private void textRow(GuiGraphics g, int y, EditBox box, PortalScreenAction action) {
        if (ro()) {
            small(g, clip(box.getValue().isEmpty() ? "-" : box.getValue(), 120), left + W - 140, y + 6, GOLD);
            return;
        }
        box.setX(left + W - 154);
        box.setY(y + 3);
        box.setWidth(110);
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
                small(g, Component.translatable("screen.bsp_teleport.portal.manual_help").getString(), left + 14, y + 5, MUTED);
                if (!ro()) {
                    int bx = left + W - 14;
                    bx = button(g, bx, y + 2, Component.translatable("screen.bsp_teleport.portal.close_now").getString(), BAD, BAD, () -> send(PortalScreenAction.CLOSE_NOW, "")) - 2;
                    button(g, bx, y + 2, Component.translatable("screen.bsp_teleport.portal.open_now").getString(), OK, OK, () -> send(PortalScreenAction.OPEN_NOW, ""));
                }
            }
            case "REDSTONE" -> {
                row(g, y, 16, DIM);
                small(g, Component.translatable("screen.bsp_teleport.portal.redstone_help").getString(), left + 14, y + 2, MUTED);
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
                small(g, real ? Component.translatable("screen.bsp_teleport.portal.real_help", data.zone()).getString() : Component.translatable("screen.bsp_teleport.portal.game_help").getString(), left + 14, y + 2, MUTED);
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
        small(g, Component.translatable("screen.bsp_teleport.portal.cooldown_help").getString(), left + 14, y + 9, MUTED);
        int bx = left + W - 14;
        bx = button(g, bx, y + 2, "+", DIM, TQ, ro() ? null : () -> send(PortalScreenAction.COOLDOWN, String.valueOf(data.cooldown() + 1))) - 2;
        String v = data.cooldown() + " s";
        int tw = (int) (font.width(v) * 0.75f) + 6;
        small(g, v, bx - tw + 3, y + 5, GOLD);
        bx -= tw + 2;
        button(g, bx, y + 2, "-", DIM, TQ, ro() ? null : () -> send(PortalScreenAction.COOLDOWN, String.valueOf(Math.max(0, data.cooldown() - 1))));
        y += 20;
        small(g, Component.translatable("screen.bsp_teleport.portal.doors_now", data.doors().isEmpty() ? "-" : data.doors()).getString(), left + 12, y + 2, MUTED);
    }

    private void drawAccess(GuiGraphics g) {
        int y = top + 46;
        small(g, Component.translatable("screen.bsp_teleport.portal.access_help").getString(), left + 12, y, MUTED);
        y += 12;
        for (String k : new String[]{"rank", "item", "node", "coins", "bought", "region"}) {
            row(g, y, 14, DIM);
            small(g, Component.translatable("screen.bsp_teleport.portal.access." + k).getString(), left + 14, y + 4, DIM);
            button(g, left + W - 14, y + 1, Component.translatable("screen.bsp_teleport.portal.later").getString(), DIM, DIM, null);
            y += 16;
        }
    }

    private void drawFooter(GuiGraphics g) {
        int y = top + H - 20;
        g.fill(left + 10, y - 2, left + W - 10, y - 1, DIM);
        small(g, data.server() + "  " + data.world() + "  " + data.pos().getX() + ", " + data.pos().getY() + ", " + data.pos().getZ(), left + 10, y + 2, MUTED);
        String frame = data.width() > 0 ? Component.translatable("screen.bsp_teleport.portal.frame", data.width(), data.height(), data.cells()).getString()
                : Component.translatable("screen.bsp_teleport.portal.no_frame").getString();
        small(g, frame, left + 10, y + 10, MUTED);
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
        small(g, label, left + 14, y + 2, TEXT);
        small(g, help, left + 14, y + 9, MUTED);
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
        int count = switch (currentPick()) {
            case "WARP" -> (int) data.warps().stream().filter(w -> !w.portal()).count();
            case "PORTAL_WARP" -> (int) data.warps().stream().filter(PortalScreenData.WarpEntry::portal).count();
            case "RANDOM" -> data.servers().size();
            default -> 0;
        };
        scroll = Math.max(0, Math.min(Math.max(0, count - 1), scroll - (int) Math.signum(delta)));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (!box1.isFocused() && !box2.isFocused() && minecraft.options.keyInventory.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}
