package com.mrgregles.bsp_teleport.client;

import com.mrgregles.bsp_teleport.band.BandAction;
import com.mrgregles.bsp_teleport.band.BandData;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.ClientConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The Teleport Band's screen, in the Holo HUD style: a tabbed list of homes, warps, players and teammates, a MORE
 * tab for spawn, back, random and the request switch, and a footer with the cooldown and the home slots.
 *
 * <p>The server decides everything: the screen only shows {@link BandData} and sends {@link BandAction}s. Starting a
 * teleport closes the screen; the warm-up counts down on the action bar.
 */
public class BandScreen extends Screen {
    private static final int W = 248, H = 196, ROW_H = 20, LIST_TOP = 30, FOOT_H = 22;
    /** B40: the replace question's key rows at most, so the list above keeps three rows even during a raid. */
    private static final int ASK_ROWS = 3;
    private static final int TQ = BandClient.TQ, BG = BandClient.BG, SLOT = BandClient.SLOT, DIM = BandClient.DIM, MUTED = BandClient.MUTED,
            TEXT = BandClient.TEXT, VIO = BandClient.VIO, GOLD = BandClient.GOLD, BAD = BandClient.BAD, OK = BandClient.OK;

    private enum Tab { HOMES, WARPS, PLAYERS, TEAM, MORE, ADMIN }

    /** Ticks between the TEAM tab's refreshes while it is open (D92: every 5 s). */
    private static final int TEAM_POLL_TICKS = 100;

    /** A clickable box drawn this frame. */
    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /** A question with Yes and No over the band (B36); only its keys answer while it shows. */
    private record Confirm(String question, Runnable yes) {
    }

    /** B40: Set home with a name in use ({@code full} false) or with every slot used; the Set home strip asks instead (option C). */
    private record SetAsk(String name, String typed, boolean full) {
    }

    private BandData data;
    @Nullable
    private Confirm confirm;
    @Nullable
    private SetAsk setAsk;
    /** Where the replace keys start in the unlocked homes, when they need more than {@link #ASK_ROWS} rows (MORE pages on). */
    private int askFrom;
    private Tab tab = Tab.HOMES;
    /** D167: the TEAM tab shows the alliance's other teams instead of the player's own. */
    private boolean allySide;
    private int scroll;
    private int left, top;
    /** Everything is drawn this many times bigger than vanilla's GUI scale; mouse positions are divided by it. */
    private float scale = 1f;
    private final List<Hit> hits = new ArrayList<>();
    private EditBox nameBox;
    private int teamTicks;

    public BandScreen(BandData data) {
        super(Component.translatable("screen.bsp_teleport.band.title"));
        this.data = data;
    }

    public void update(BandData data) {
        this.data = data;
        // B40: a question whose reason went away (the home was deleted, a slot came free) closes, and the name comes back.
        if (setAsk != null && !asks(setAsk.name(), setAsk.full())) {
            keepHome();
        }
    }

    /** Whether Set home with this name asks: {@code full} false for a name in use, true for every slot used. */
    private boolean asks(String name, boolean full) {
        boolean taken = data.homes().stream().anyMatch(h -> h.name().equals(name));
        if (!full) {
            return taken;
        }
        return !taken && TeleportData.validName(name) && data.used() >= data.slots() && data.homes().stream().anyMatch(h -> !h.locked());
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
        nameBox.visible = tab == Tab.HOMES && confirm == null && setAsk == null;
        g.fill(left - 1, top - 1, left + W + 1, top + H + 1, TQ);
        g.fill(left, top, left + W, top + H, BG);
        Tab[] tabs = data.admin() ? Tab.values() : new Tab[] {Tab.HOMES, Tab.WARPS, Tab.PLAYERS, Tab.TEAM, Tab.MORE};
        int tabW = data.admin() ? 36 : 40;
        // The title gives way to the tab row: the short name when the long one would run under the first tab.
        int firstTab = left + W - 10 - tabs.length * (tabW + 2);
        String heading = title.getString().toUpperCase();
        if (left + 10 + font.width(heading) > firstTab - 4) {
            heading = Component.translatable("screen.bsp_teleport.band.title_short").getString().toUpperCase();
        }
        // Audit: with six tabs even the short title would sit under the first one, so it is left out.
        if (left + 10 + font.width(heading) <= firstTab - 4) {
            g.drawString(font, heading, left + 10, top + 8, TQ, false);
        }
        small(g, clip(Component.translatable(tab == Tab.TEAM ? allySide ? "screen.bsp_teleport.band.ally_help" : "screen.bsp_teleport.band.team_help" : "screen.bsp_teleport.band.help").getString(), W - 20), left + 10, top + 19, MUTED);

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
                teamTicks = 0;
                setAsk = null;
                nameBox.visible = tab == Tab.HOMES;
            }));
        }

        int listBottom = top + H - FOOT_H - (tab == Tab.HOMES ? 24 + askRows().size() * 14 : 4);
        if (data.raid()) {
            small(g, clip(Component.translatable("screen.bsp_teleport.band.raid", data.raidDistance()).getString(), W - 20), left + 10, top + LIST_TOP - 4, BAD);
        }
        int y = top + LIST_TOP + (data.raid() ? 8 : 0);
        switch (tab) {
            case HOMES -> drawHomes(g, y, listBottom);
            case WARPS -> drawWarps(g, y, listBottom);
            case PLAYERS -> drawPlayers(g, y, listBottom);
            case TEAM -> drawTeam(g, y, listBottom);
            case MORE -> drawMore(g, y, listBottom);
            case ADMIN -> {
            }
        }
        drawFooter(g);
        super.render(g, (int) (mouseX / scale), (int) (mouseY / scale), partial);
        if (confirm != null) {
            drawConfirm(g);
        }
        g.pose().popPose();
    }

    /** B36: the admin panel's question box, over the whole band. */
    private void drawConfirm(GuiGraphics g) {
        int cw = Math.min(200, W - 20), ch = 50;
        int cx = left + (W - cw) / 2, cy = top + (H - ch) / 2;
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        g.fill(left, top, left + W, top + H, 0xA0000000);
        g.fill(cx - 1, cy - 1, cx + cw + 1, cy + ch + 1, GOLD);
        g.fill(cx, cy, cx + cw, cy + ch, BG);
        int ty = cy + 8;
        for (net.minecraft.util.FormattedCharSequence line : font.split(Component.literal(confirm.question()), cw - 16)) {
            g.drawString(font, line, cx + 8, ty, TEXT, false);
            ty += 10;
        }
        hits.clear(); // only the box's keys answer while it is up
        Confirm c = confirm;
        int bx = button(g, cx + cw - 8, cy + ch - 18, Component.translatable("screen.bsp_teleport.admin.no").getString(), DIM, MUTED, () -> confirm = null) - 4;
        button(g, bx, cy + ch - 18, Component.translatable("screen.bsp_teleport.admin.yes").getString(), OK, OK, () -> {
            confirm = null;
            c.yes().run();
        });
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
            String state = Component.translatable(h.locked() ? "screen.bsp_teleport.band.locked" : h.nearRaid() ? "screen.bsp_teleport.band.near_raid" : "screen.bsp_teleport.band.go").getString();
            int keysLeft = left + W - 10 - keyW(Component.translatable("screen.bsp_teleport.band.del").getString()) - 2 - keyW(state) - 4;
            row(g, y, h.name(), sub, nameColour, h.server().equals(data.server()) ? MUTED : VIO, keysLeft);
            icon(g, y, h.icon(), com.mrgregles.bsp_teleport.util.Icons.DEFAULT_HOME, () -> send(BandAction.HOME_ICON, h.name()));
            int bx = left + W - 10;
            bx = button(g, bx, y + 4, Component.translatable("screen.bsp_teleport.band.del").getString(), DIM, MUTED, 
                    () -> confirm = new Confirm(Component.translatable("screen.bsp_teleport.band.confirm_del_home", h.name()).getString(),
                            () -> send(BandAction.DEL_HOME, h.name())));
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
        if (setAsk != null) {
            drawSetAsk(g, sy);
            return;
        }
        button(g, left + W - 10, sy + 1, Component.translatable("screen.bsp_teleport.band.set_here").getString(), GOLD, GOLD, this::setHomeHere);
    }

    /** B40: asks first when the name is in use or every slot is used; the server checks again either way. */
    private void setHomeHere() {
        String typed = nameBox.getValue().trim();
        nameBox.setValue("");
        String name = TeleportData.normalise(typed.isEmpty() ? "home" : typed);
        boolean taken = asks(name, false);
        if (taken || asks(name, true)) {
            setAsk = new SetAsk(name, typed, !taken);
            askFrom = 0;
            nameBox.setFocused(false);
            setFocused(null);
        } else {
            send(BandAction.SET_HOME, typed);
        }
    }

    /** KEEP (or Esc): nothing changes and the typed name comes back. */
    private void keepHome() {
        if (setAsk != null) {
            nameBox.setValue(setAsk.typed());
        }
        setAsk = null;
    }

    /** Marks the MORE key in {@link #askRows}. */
    private static final String MORE = "\u0000more";

    /**
     * The replace question's keys in rows that fit the band: unlocked homes from {@link #askFrom}, then MORE when the
     * rest do not fit in {@link #ASK_ROWS} rows, then KEEP (null). Empty when not asking that.
     */
    private List<List<String>> askRows() {
        if (tab != Tab.HOMES || setAsk == null || !setAsk.full()) {
            return new ArrayList<>();
        }
        List<String> homes = new ArrayList<>();
        data.homes().stream().filter(h -> !h.locked()).forEach(h -> homes.add(h.name()));
        if (askFrom >= homes.size()) {
            askFrom = 0;
        }
        List<String> rest = homes.subList(askFrom, homes.size());
        List<List<String>> rows = layoutKeys(rest, askFrom > 0);
        for (int n = rest.size() - 1; rows.size() > ASK_ROWS && n >= 1; n--) {
            rows = layoutKeys(rest.subList(0, n), true);
        }
        return rows;
    }

    /** Lays keys out left to right in rows no wider than the list; {@code more} adds MORE before KEEP. */
    private List<List<String>> layoutKeys(List<String> homes, boolean more) {
        List<String> keys = new ArrayList<>(homes);
        if (more) {
            keys.add(MORE);
        }
        keys.add(null);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        int used = 0;
        for (String k : keys) {
            int w = keyW(askLabel(k)) + 3;
            if (!row.isEmpty() && used + w > W - 20) {
                rows.add(row);
                row = new ArrayList<>();
                used = 0;
            }
            row.add(k);
            used += w;
        }
        rows.add(row);
        return rows;
    }

    private String askLabel(@Nullable String key) {
        return key == null ? keepLabel() : key.equals(MORE) ? Component.translatable("screen.bsp_teleport.band.ask_more").getString() : key;
    }

    private String keepLabel() {
        return Component.translatable("screen.bsp_teleport.band.keep").getString();
    }

    /** B40 option C: the Set home strip becomes the question, with a gold line above it; the list above stays usable. */
    private void drawSetAsk(GuiGraphics g, int sy) {
        SetAsk ask = setAsk;
        if (!ask.full()) {
            g.fill(left + 10, sy - 3, left + W - 10, sy - 2, GOLD);
            int bx = button(g, left + W - 10, sy + 1, keepLabel(), DIM, MUTED, this::keepHome) - 3;
            bx = button(g, bx, sy + 1, Component.translatable("screen.bsp_teleport.band.move").getString(), GOLD, GOLD, () -> {
                setAsk = null;
                send(BandAction.MOVE_HOME, ask.name());
            });
            small(g, clip(Component.translatable("screen.bsp_teleport.band.ask_move", ask.name()).getString(), bx - left - 16), left + 10, sy + 4, TEXT);
            return;
        }
        List<List<String>> rows = askRows();
        int qy = sy - rows.size() * 14;
        g.fill(left + 10, qy, left + W - 10, qy + 1, GOLD);
        small(g, clip(Component.translatable("screen.bsp_teleport.band.ask_replace", data.slots(), ask.name()).getString(), W - 20), left + 10, qy + 4, TEXT);
        int ry = qy + 14;
        int shown = (int) rows.stream().flatMap(List::stream).filter(k -> k != null && !k.equals(MORE)).count();
        for (List<String> row : rows) {
            int x = left + 10;
            for (String k : row) {
                String label = askLabel(k);
                Runnable action;
                if (k == null) {
                    action = this::keepHome;
                } else if (k.equals(MORE)) {
                    action = () -> askFrom += shown; // past the last home it starts again at the first
                } else {
                    action = () -> {
                        setAsk = null;
                        send(BandAction.REPLACE_HOME, k + " " + ask.name());
                    };
                }
                boolean home = k != null && !k.equals(MORE);
                button(g, x + keyW(label), ry, label, home ? GOLD : DIM, home ? GOLD : MUTED, action);
                x += keyW(label) + 3;
            }
            ry += 14;
        }
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
                // D163: the cost names its currency, bank pieces or coins carried.
                sub += "  " + (w.pieces() ? Component.translatable("screen.bsp_teleport.band.pieces", String.format(java.util.Locale.ROOT, "%,d", w.cost()))
                        : Component.translatable("screen.bsp_teleport.band.coins", w.cost())).getString();
            }
            String wkey = Component.translatable(w.nearRaid() ? "screen.bsp_teleport.band.near_raid" : "screen.bsp_teleport.band.go").getString();
            // D150: a warp with open hours carries a chip before its key: OPEN, or OPENS 03:00 on a red-edged row.
            boolean closed = w.opensAt() >= 0;
            String chip = w.opensAt() == -1 || w.nearRaid() ? null : closed
                    ? Component.translatable("screen.bsp_teleport.band.opens", com.mrgregles.bsp_teleport.access.OpenHours.hhmm(w.opensAt())).getString()
                    : Component.translatable("screen.bsp_teleport.band.open").getString();
            int textRight = left + W - 10 - keyW(wkey) - 4 - (chip == null ? 0 : keyW(chip) + 2);
            row(g, y, w.name(), sub, w.nearRaid() ? BAD : TEXT, w.server().equals(data.server()) ? MUTED : VIO, textRight);
            if (closed) {
                outline(g, y, BAD);
            }
            icon(g, y, w.icon(), com.mrgregles.bsp_teleport.util.Icons.DEFAULT_WARP, null);
            if (w.nearRaid()) {
                button(g, left + W - 10, y + 4, Component.translatable("screen.bsp_teleport.band.near_raid").getString(), BAD, BAD, null);
            } else {
                boolean afford = w.afford();
                // A closed warp's key stays pressable but dim: admins pass the hours, and everyone else is told when it opens.
                // B23: the same for a warp the player cannot pay for: the server says what it costs, where a dead key said nothing.
                int keyX = button(g, left + W - 10, y + 4, Component.translatable("screen.bsp_teleport.band.go").getString(), closed ? DIM : w.cost() > 0 ? GOLD : TQ,
                        closed || !afford ? MUTED : w.cost() > 0 ? GOLD : TQ, () -> go(BandAction.WARP, w.name()));
                if (chip != null) {
                    button(g, keyX - 2, y + 4, chip, closed ? BAD : OK, closed ? BAD : OK, null);
                }
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
            int bx = button(g, left + W - 14, y + 4, Component.translatable("screen.bsp_teleport.band.deny").getString(), BAD, BAD, () -> send(BandAction.DENY, ""));
            int ax = button(g, bx - 2, y + 4, Component.translatable("screen.bsp_teleport.band.accept").getString(), OK, OK, () -> go(BandAction.ACCEPT, ""));
            // Audit: the request line is small and cut before the keys.
            small(g, clip(head, ax - (left + 14) - 4), left + 14, y + 3, VIO);
            // B27: the server comes only for a teammate (or to an admin).
            Component timeLeft = r.server().isEmpty() ? Component.translatable("screen.bsp_teleport.band.request_time_left", r.secondsLeft())
                    : Component.translatable("screen.bsp_teleport.band.request_left", r.server(), r.secondsLeft());
            small(g, clip(timeLeft.getString(), ax - (left + 14) - 4), left + 14, y + 12, MUTED);
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
            row(g, y, p.name(), p.server(), TEXT, p.server().isEmpty() || p.server().equals(data.server()) ? MUTED : VIO);
            int bx = button(g, left + W - 10, y + 4, Component.translatable("screen.bsp_teleport.band.here").getString(), DIM, MUTED, () -> send(BandAction.TPA_HERE, p.name()));
            button(g, bx - 2, y + 4, Component.translatable("screen.bsp_teleport.band.tpa").getString(), TQ, TQ, () -> send(BandAction.TPA, p.name()));
            y += ROW_H + 2;
        }
    }

    /** The TEAM tab (D92, Roster layout): the team banner, then one row per teammate with their state and a GO key. */
    private void drawTeam(GuiGraphics g, int y, int bottom) {
        BandData.TeamEntry team = data.team();
        if (team == null) {
            small(g, Component.translatable("screen.bsp_teleport.band.no_team").getString(), left + 10, y + 2, TEXT);
            small(g, Component.translatable("screen.bsp_teleport.band.no_team_hint").getString(), left + 10, y + 12, MUTED);
            return;
        }
        if (team.alliance().isEmpty()) {
            allySide = false;
        } else {
            y = drawTeamSwitch(g, y, team.alliance());
        }
        if (allySide) {
            drawAllies(g, y, bottom, team.allies());
            return;
        }
        int colour = 0xFF000000 | team.colour();
        long online = team.mates().stream().filter(m -> !"off".equals(m.state())).count() + 1;
        g.fill(left + 10, y, left + W - 10, y + ROW_H, colour);
        g.fill(left + 11, y + 1, left + W - 11, y + ROW_H - 1, SLOT);
        g.drawString(font, team.name(), left + 14, y + 3, colour, false);
        small(g, Component.translatable("screen.bsp_teleport.band.team_line", team.leader(), team.mates().size() + 1, online).getString(), left + 14, y + 12, MUTED);
        y += ROW_H + 4;
        int i = 0;
        for (BandData.MateEntry m : team.mates()) {
            if (i++ < scroll) {
                continue;
            }
            if (y + ROW_H > bottom) {
                break;
            }
            boolean off = "off".equals(m.state());
            String state = switch (m.state()) {
                case "here" -> Component.translatable("screen.bsp_teleport.band.mate_here").getString();
                case "far" -> Component.translatable("screen.bsp_teleport.band.mate_on", m.server()).getString();
                default -> m.seen().isEmpty() ? Component.translatable("screen.bsp_teleport.band.mate_offline").getString()
                        : Component.translatable("screen.bsp_teleport.band.mate_seen", m.seen()).getString();
            };
            if (m.nearRaid()) {
                state += "  " + Component.translatable("screen.bsp_teleport.band.steal_near", data.raidDistance()).getString();
            }
            int subColour = "far".equals(m.state()) ? VIO : "here".equals(m.state()) ? OK : MUTED;
            row(g, y, m.name(), state, off ? MUTED : m.nearRaid() ? BAD : TEXT, m.nearRaid() ? BAD : subColour);
            if (m.leader()) {
                small(g, Component.translatable("screen.bsp_teleport.band.leader").getString(), left + 14 + font.width(m.name()) + 4, y + 4, GOLD);
            }
            if (off) {
                y += ROW_H + 2;
                continue;
            }
            if (m.nearRaid()) {
                button(g, left + W - 10, y + 4, Component.translatable("screen.bsp_teleport.band.raid_key").getString(), BAD, BAD, null);
            } else if (!m.reachable()) {
                button(g, left + W - 10, y + 4, Component.translatable("screen.bsp_teleport.band.far").getString(), DIM, MUTED, null);
            } else {
                int keyColour = "far".equals(m.state()) ? VIO : TQ;
                button(g, left + W - 10, y + 4, Component.translatable("screen.bsp_teleport.band.go").getString(), keyColour, keyColour, () -> go(BandAction.TEAM_GO, m.uuid()));
            }
            y += ROW_H + 2;
        }
    }

    /** D167: MY TEAM and ALLIES side by side across the list, only when the team is in an alliance. */
    private int drawTeamSwitch(GuiGraphics g, int y, String alliance) {
        int w = (W - 22) / 2;
        String[] labels = {Component.translatable("screen.bsp_teleport.band.my_team").getString(),
                Component.translatable("screen.bsp_teleport.band.allies", alliance).getString()};
        for (int i = 0; i < 2; i++) {
            int x = left + 10 + i * (w + 2);
            boolean on = (i == 1) == allySide;
            g.fill(x, y, x + w, y + 13, on ? TQ : DIM);
            g.fill(x + 1, y + 1, x + w - 1, y + 12, SLOT);
            String label = clip(labels[i].toUpperCase(), w - 8);
            small(g, label, x + (w - (int) (font.width(label) * 0.75f)) / 2, y + 4, on ? TQ : MUTED);
            boolean side = i == 1;
            hits.add(new Hit(x, y, w, 13, () -> {
                if (allySide != side) {
                    allySide = side;
                    scroll = 0;
                }
            }));
        }
        return y + 17;
    }

    /** D167: one row per ally, their team's name in its colour after the player's, ASK (or FAR, RAID) for online ones. */
    private void drawAllies(GuiGraphics g, int y, int bottom, List<BandData.AllyEntry> allies) {
        if (allies.isEmpty()) {
            small(g, Component.translatable("screen.bsp_teleport.band.no_allies").getString(), left + 10, y + 2, MUTED);
            return;
        }
        int i = 0;
        for (BandData.AllyEntry a : allies) {
            if (i++ < scroll) {
                continue;
            }
            if (y + ROW_H > bottom) {
                break;
            }
            boolean off = "off".equals(a.state());
            String where = switch (a.state()) {
                case "here" -> Component.translatable("screen.bsp_teleport.band.mate_here").getString();
                case "far" -> Component.translatable("screen.bsp_teleport.band.mate_on", a.server()).getString();
                default -> a.seen().isEmpty() ? Component.translatable("screen.bsp_teleport.band.mate_offline").getString()
                        : Component.translatable("screen.bsp_teleport.band.mate_seen", a.seen()).getString();
            };
            if (!off && !a.dimension().isEmpty()) {
                where += "  " + dimensionName(a.dimension());
            }
            if (!off && !a.coords().isEmpty()) {
                where += "  " + a.coords();
            }
            if (a.nearRaid()) {
                where += "  " + Component.translatable("screen.bsp_teleport.band.steal_near", data.raidDistance()).getString();
            }
            String key = off ? null : Component.translatable(a.nearRaid() ? "screen.bsp_teleport.band.raid_key"
                    : !a.reachable() ? "screen.bsp_teleport.band.far" : "screen.bsp_teleport.band.ask").getString();
            int textRight = (key == null ? left + W - 10 : left + W - 10 - keyW(key)) - 4;
            int subColour = "far".equals(a.state()) ? VIO : "here".equals(a.state()) ? OK : MUTED;
            row(g, y, a.name(), where, off ? MUTED : a.nearRaid() ? BAD : TEXT, a.nearRaid() ? BAD : subColour, textRight);
            int tagX = left + 14 + Math.min(font.width(a.name()), textRight - left - 14) + 4;
            if (textRight - tagX > 16) {
                small(g, clip(a.teamName(), textRight - tagX), tagX, y + 4, 0xFF000000 | a.colour());
            }
            if (key != null) {
                if (a.nearRaid()) {
                    button(g, left + W - 10, y + 4, key, BAD, BAD, null);
                } else if (!a.reachable()) {
                    button(g, left + W - 10, y + 4, key, DIM, MUTED, null);
                } else {
                    button(g, left + W - 10, y + 4, key, GOLD, GOLD, () -> go(BandAction.ALLY_ASK, a.uuid()));
                }
            }
            y += ROW_H + 2;
        }
    }

    /** "minecraft:the_nether" as "the nether"; other mods' worlds keep their path. */
    private static String dimensionName(String id) {
        int colon = id.indexOf(':');
        return (colon >= 0 ? id.substring(colon + 1) : id).replace('_', ' ');
    }

    private void drawMore(GuiGraphics g, int y, int bottom) {
        int x = left + 10;
        y = wide(g, x, y, Component.translatable("screen.bsp_teleport.band.spawn").getString(), data.hasSpawn() ? TQ : DIM, data.hasSpawn() ? () -> go(BandAction.SPAWN, "") : null);
        y = wide(g, x, y, Component.translatable("screen.bsp_teleport.band.back").getString(), data.hasBack() ? TQ : DIM, data.hasBack() ? () -> go(BandAction.BACK, "") : null);
        y = wide(g, x, y, Component.translatable("screen.bsp_teleport.band.rtp_here").getString(), TQ, () -> go(BandAction.RTP, ""));
        // Audit: room is kept for the ignore and pay-to-skip rows, so a long server list never pushes them past the footer.
        boolean skip = data.skipCoins() > 0 && data.cooldownLeft() > 0;
        int reserve = 4 + 16 + (skip ? 16 : 0);
        for (String server : data.rtpServers()) {
            if (y + 16 > bottom - reserve) {
                break;
            }
            y = wide(g, x, y, Component.translatable("screen.bsp_teleport.band.rtp_on", server).getString(), VIO, () -> go(BandAction.RTP, server));
        }
        y += 4;
        y = wide(g, x, y, Component.translatable(data.ignoring() ? "screen.bsp_teleport.band.ignore_on" : "screen.bsp_teleport.band.ignore_off").getString(),
                data.ignoring() ? GOLD : DIM, () -> send(BandAction.TOGGLE_IGNORE, ""));
        if (skip && y + 16 <= bottom) {
            y = wide(g, x, y, Component.translatable("screen.bsp_teleport.band.tpnow", data.skipCoins()).getString(), GOLD, () -> go(BandAction.TPNOW, ""));
        }
        if (!data.accessOn() || y + 2 + 9 + 14 > bottom) {
            return;
        }
        // The ACCESS list (D80): one line per restricted server, and the HUD layout the player draws it with.
        y += 2;
        small(g, Component.translatable("screen.bsp_teleport.band.access").getString().toUpperCase(), x, y, MUTED);
        y += 9;
        for (BandData.AccessEntry a : data.access()) {
            if (y + 14 > bottom) {
                break;
            }
            String state = switch (a.state()) {
                case "main" -> Component.translatable("screen.bsp_teleport.band.access_main").getString();
                case "full" -> Component.translatable("screen.bsp_teleport.band.access_full").getString();
                case "rent" -> Component.translatable("screen.bsp_teleport.band.access_rent", a.left()).getString();
                default -> Component.translatable("screen.bsp_teleport.band.access_none", a.token() > 0 ? "Access Token " + com.mrgregles.bsp_teleport.access.TokenAssignment.roman(a.token()) : "-").getString();
            };
            int colour = switch (a.state()) {
                case "main" -> GOLD;
                case "full" -> TQ;
                case "rent" -> VIO;
                default -> DIM;
            };
            g.fill(x, y, x + W - 20, y + 12, colour == DIM ? DIM : colour);
            g.fill(x + 1, y + 1, x + W - 21, y + 11, SLOT);
            if (a.token() > 0) {
                g.pose().pushPose();
                g.pose().translate(x + 2, y + 1, 0);
                g.pose().scale(0.6f, 0.6f, 1f);
                g.renderItem(new net.minecraft.world.item.ItemStack(com.mrgregles.bsp_teleport.registry.ModItems.ACCESS_TOKENS[a.token() - 1].get()), 0, 0);
                g.pose().popPose();
            }
            small(g, a.server() + "  " + state, x + 16, y + 3, colour == DIM ? MUTED : colour);
            y += 14;
        }
        boolean noMain = data.access().stream().noneMatch(a -> "main".equals(a.state()));
        if (noMain && !data.access().isEmpty() && y + 16 <= bottom) {
            y = wide(g, x, y, Component.translatable("screen.bsp_teleport.band.choose_main").getString(), GOLD, () -> go(BandAction.ACCESS_CHOOSE, ""));
        }
        if (y + 14 <= bottom) {
            String layout = Component.translatable("screen.bsp_teleport.band.hud_" + com.mrgregles.bsp_teleport.ClientConfig.accessHud()).getString();
            wide(g, x, y, Component.translatable("screen.bsp_teleport.band.hud", layout).getString(), VIO, com.mrgregles.bsp_teleport.ClientConfig::cycleAccessHud);
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
        small(g, clip(next, W - 110), left + 100, y + 9, MUTED);
        if (data.swiftLevel() > 0) {
            small(g, clip(Component.translatable("screen.bsp_teleport.band.swift", data.swiftLevel(), data.swiftLevel() * 25).getString(), W - 110), left + 100, y, MUTED);
        }
    }

    // Pieces

    private void row(GuiGraphics g, int y, String name, String sub, int nameColour, int subColour) {
        row(g, y, name, sub, nameColour, subColour, left + W - 14);
    }

    /** A list row whose name and detail are cut to end before {@code textRight} (the keys' left edge), audit. */
    private void row(GuiGraphics g, int y, String name, String sub, int nameColour, int subColour, int textRight) {
        g.fill(left + 10, y, left + W - 10, y + ROW_H, DIM);
        g.fill(left + 11, y + 1, left + W - 11, y + ROW_H - 1, SLOT);
        boolean withIcon = tab == Tab.HOMES || tab == Tab.WARPS;
        int tx = withIcon ? left + 32 : left + 14;
        int room = Math.max(10, textRight - tx);
        String shown = name;
        while (shown.length() > 3 && font.width(shown) > room) {
            shown = shown.substring(0, shown.length() - 1);
        }
        g.drawString(font, shown.length() < name.length() ? shown.substring(0, Math.max(1, shown.length() - 2)) + ".." : name, tx, y + 3, nameColour, false);
        small(g, clip(sub, room), tx, y + 12, subColour);
    }

    /** A 1 px edge in {@code colour} round a list row, over the dim one. */
    private void outline(GuiGraphics g, int y, int colour) {
        g.fill(left + 10, y, left + W - 10, y + 1, colour);
        g.fill(left + 10, y + ROW_H - 1, left + W - 10, y + ROW_H, colour);
        g.fill(left + 10, y, left + 11, y + ROW_H, colour);
        g.fill(left + W - 11, y, left + W - 10, y + ROW_H, colour);
    }

    /** The width a key with this label takes (keys draw their label in capitals). */
    private int keyW(String label) {
        return (int) (font.width(label.toUpperCase()) * 0.75f) + 8;
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

    /** The item icon at the row's left; clicking it (when {@code onClick} is given) makes it the held item. */
    private void icon(GuiGraphics g, int y, String icon, String fallback, @Nullable Runnable onClick) {
        g.renderItem(com.mrgregles.bsp_teleport.util.Icons.stack(icon, fallback), left + 13, y + 2);
        if (onClick != null) {
            hits.add(new Hit(left + 12, y + 1, 18, 18, onClick));
        }
    }

    /** A small right-aligned button; returns the x where the next one to its left ends. */
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
        if (confirm != null) {
            return true; // a click beside the box does nothing, so the list under it cannot be used by mistake
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
        if (confirm != null) {
            return true;
        }
        int count = switch (tab) {
            case HOMES -> data.homes().size();
            case WARPS -> data.warps().size();
            case PLAYERS -> data.players().size();
            case TEAM -> data.team() == null ? 0 : allySide ? data.team().allies().size() : data.team().mates().size();
            case MORE, ADMIN -> 0;
        };
        scroll = Math.max(0, Math.min(Math.max(0, count - 1), scroll - (int) Math.signum(delta)));
        return true;
    }

    /** While the TEAM tab is open the server is asked for fresh data every few seconds (presence, who joined or left). */
    @Override
    public void tick() {
        super.tick();
        if (tab == Tab.TEAM && ++teamTicks >= TEAM_POLL_TICKS) {
            teamTicks = 0;
            send(BandAction.TEAM_REFRESH, "");
        }
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (confirm == null && setAsk != null && key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            keepHome(); // B40: Esc answers KEEP and leaves the band open
            return true;
        }
        if (confirm != null) {
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                confirm = null; // Esc answers No and leaves the band open
                return true;
            }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                return true; // never a Yes by keyboard
            }
        }
        if (setAsk == null && nameBox.isFocused() && key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) {
            setHomeHere();
            return true;
        }
        if (!nameBox.isFocused() && (BandClient.OPEN_KEY.matches(key, scan) || minecraft.options.keyInventory.matches(key, scan))) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}
