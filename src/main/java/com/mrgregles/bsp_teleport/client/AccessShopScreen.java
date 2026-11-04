package com.mrgregles.bsp_teleport.client;

import com.mrgregles.bsp_teleport.ClientConfig;
import com.mrgregles.bsp_teleport.access.AccessGrant;
import com.mrgregles.bsp_teleport.access.TokenAssignment;
import com.mrgregles.bsp_teleport.block.AccessShopBlockEntity;
import com.mrgregles.bsp_teleport.network.BandPackets;
import com.mrgregles.bsp_teleport.registry.ModItems;
import com.mrgregles.bsp_teleport.shop.ShopEntry;
import com.mrgregles.bsp_teleport.shop.ShopTab;
import com.mrgregles.bsp_teleport.shop.ShopView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.mrgregles.bsp_teleport.client.BandClient.BG;
import static com.mrgregles.bsp_teleport.client.BandClient.BAD;
import static com.mrgregles.bsp_teleport.client.BandClient.DIM;
import static com.mrgregles.bsp_teleport.client.BandClient.GOLD;
import static com.mrgregles.bsp_teleport.client.BandClient.MUTED;
import static com.mrgregles.bsp_teleport.client.BandClient.OK;
import static com.mrgregles.bsp_teleport.client.BandClient.SLOT;
import static com.mrgregles.bsp_teleport.client.BandClient.TEXT;
import static com.mrgregles.bsp_teleport.client.BandClient.TQ;
import static com.mrgregles.bsp_teleport.client.BandClient.VIO;

/**
 * The Access Shop screen (layout B, D174): tabs SERVERS, PORTALS, WARPS; a card per token with a RENT / FULL switch, the
 * chosen tier's price and BUY; BUY asks USE NOW, TAKE TOKEN or CANCEL (D175). An admin also gets SETTINGS (D179): the
 * shop's name and message, Copy from, a 4-wide grid of slots per tab and the picked slot's prices and hours.
 *
 * <p>What each token opens and what the player holds come from {@link ClientAccess}; the server checks every purchase.
 */
public class AccessShopScreen extends Screen {
    private static final int W = 280, H = 212, PAD = 10, HEAD = 22, CARD_H = 64, CARD_COLS = 3, SLOT_H = 26, SLOT_COLS = 4, GAP = 4;

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /**
     * BUY was pressed: entry {@code index} at the full tier or rent; answered USE NOW, TAKE TOKEN or CANCEL. The token and
     * price shown go with the answer, so the server can refuse if the shop changed meanwhile.
     */
    private record Ask(int index, int token, int price, boolean full, String question) {
    }

    private ShopView view;
    private ShopTab tab = ShopTab.SERVERS;
    private int scroll;
    private boolean settings;
    private List<ShopEntry> edit;
    private int selected = -1;
    private int source;
    @Nullable
    private Ask ask;
    private String note = "";
    /** The tier each card's switch shows, by entry index: true for FULL. */
    private final Map<Integer, Boolean> fullPick = new HashMap<>();
    private final List<Hit> hits = new ArrayList<>();
    private float scale = 1f;
    private int left, top;
    private EditBox nameBox, messageBox, hoursBox, rentBox, fullBox;
    /** True while the boxes are being filled from the picked slot, so their responders do not write back. */
    private boolean loading;

    private AccessShopScreen(ShopView view) {
        super(Component.translatable("screen.bsp_teleport.shop.title"));
        this.view = view;
        this.edit = new ArrayList<>(view.entries());
    }

    /** A view from the server: refreshes the open screen of the same shop, else opens one. */
    public static void show(ShopView view) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof AccessShopScreen open && open.view.pos().equals(view.pos())) {
            open.update(view);
        } else {
            mc.setScreen(new AccessShopScreen(view));
        }
    }

    private void update(ShopView view) {
        this.view = view;
        this.ask = null;
        fullPick.clear(); // the picks are by place in the list, which may have changed
        if (!settings) {
            edit = new ArrayList<>(view.entries());
        } else {
            // a save or copy came back: show what the shop now holds
            edit = new ArrayList<>(view.entries());
            nameBox.setValue(view.name());
            messageBox.setValue(view.message());
            select(Math.min(selected, edit.size() - 1));
        }
    }

    @Override
    protected void init() {
        scale = ClientConfig.screenScale(width, height, W, H);
        left = ((int) (width / scale) - W) / 2;
        top = ((int) (height / scale) - H) / 2;
        nameBox = box(left + PAD + 30, top + HEAD + 3, 74, AccessShopBlockEntity.NAME_MAX, view.name(), null);
        messageBox = box(left + PAD + 150, top + HEAD + 3, W - 2 * PAD - 150, AccessShopBlockEntity.MESSAGE_MAX, view.message(), null);
        int ey = editorTop();
        hoursBox = box(left + PAD + 76, ey + 17, 34, 5, "", v -> change(e -> new ShopEntry(e.token(), e.rent(), number(v, 1), e.rentPrice(), e.rentPieces(), e.full(), e.fullPrice(), e.fullPieces())));
        rentBox = box(left + PAD + 140, ey + 17, 62, 10, "", v -> change(e -> new ShopEntry(e.token(), e.rent(), e.rentHours(), number(v, 0), e.rentPieces(), e.full(), e.fullPrice(), e.fullPieces())));
        fullBox = box(left + PAD + 140, ey + 34, 62, 10, "", v -> change(e -> new ShopEntry(e.token(), e.rent(), e.rentHours(), e.rentPrice(), e.rentPieces(), e.full(), number(v, 0), e.fullPieces())));
        for (EditBox b : new EditBox[]{hoursBox, rentBox, fullBox}) {
            b.setFilter(s -> s.matches("\\d*"));
        }
        select(selected);
    }

    private EditBox box(int x, int y, int w, int max, String value, @Nullable java.util.function.Consumer<String> responder) {
        EditBox b = new EditBox(font, x, y, w, 12, Component.empty());
        b.setMaxLength(max);
        b.setValue(value);
        b.setBordered(true);
        if (responder != null) {
            b.setResponder(v -> {
                if (!loading) {
                    responder.accept(v);
                }
            });
        }
        b.visible = false;
        addRenderableWidget(b);
        return b;
    }

    /** The box's number, at least {@code min}; audit: a number too big for an int is the most a price may be, never 0. */
    private static int number(String v, int min) {
        if (v.isEmpty()) {
            return min;
        }
        try {
            return (int) Math.max(min, Math.min(ShopEntry.MAX_PRICE, Long.parseLong(v)));
        } catch (NumberFormatException e) {
            return ShopEntry.MAX_PRICE;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // What the client knows about a token

    @Nullable
    private static TokenAssignment assignment(ShopEntry e) {
        return ClientAccess.token(e.token());
    }

    private static ShopTab tabOf(ShopEntry e) {
        TokenAssignment t = assignment(e);
        return ShopTab.of(t == null ? AccessGrant.Kind.SERVER : t.kind());
    }

    /** The player's live grant for what this token opens, or null. */
    @Nullable
    private static ClientAccess.Entry held(ShopEntry e) {
        TokenAssignment t = assignment(e);
        if (t == null) {
            return null;
        }
        for (ClientAccess.Entry g : ClientAccess.grants()) {
            if (g.kind() == t.kind() && g.target().equalsIgnoreCase(t.target()) && g.remainingMs() > 0) {
                return g;
            }
        }
        return null;
    }

    private static String price(int amount, boolean pieces) {
        return amount <= 0 ? tr("screen.bsp_teleport.shop.free") : String.format("%,d", amount) + (pieces ? "p" : "c");
    }

    private boolean affords(ShopEntry e, boolean full) {
        int p = e.price(full);
        return p <= 0 || (e.pieces(full) ? view.pieces() >= p : view.coins() >= p);
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    // Drawing

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1f);
        hits.clear();
        g.fill(left - 1, top - 1, left + W + 1, top + H + 1, settings ? VIO : TQ);
        g.fill(left, top, left + W, top + H, BG);
        boolean editing = settings && ask == null;
        nameBox.visible = editing;
        messageBox.visible = editing;
        boolean slot = editing && selected >= 0 && selected < edit.size();
        hoursBox.visible = slot;
        rentBox.visible = slot;
        fullBox.visible = slot;
        if (settings) {
            drawSettings(g);
        } else {
            drawShop(g);
        }
        if (!note.isEmpty()) {
            small(g, clip(note, W - 2 * PAD), left + PAD, top + H - 11, GOLD);
        }
        super.render(g, (int) (mouseX / scale), (int) (mouseY / scale), partial);
        if (ask != null) {
            drawAsk(g);
        }
        g.pose().popPose();
    }

    private void drawShop(GuiGraphics g) {
        String wallet = tr("screen.bsp_teleport.shop.wallet", String.format("%,d", view.pieces()), String.format("%,d", view.coins()));
        int wx = left + W - PAD;
        if (view.admin()) {
            wx = key(g, wx, top + 6, tr("screen.bsp_teleport.shop.settings"), VIO, VIO, () -> {
                settings = true;
                edit = new ArrayList<>(view.entries());
                nameBox.setValue(view.name());
                messageBox.setValue(view.message());
                scroll = 0;
                note = "";
                select(-1);
            }) - 6;
        }
        int walletX = wx - (int) (font.width(wallet) * 0.75f);
        small(g, wallet, walletX, top + 9, GOLD);
        g.drawString(font, clipFull(view.name().toUpperCase(), walletX - left - PAD - 6), left + PAD, top + 8, TQ, false);
        int y = drawTabs(g, top + HEAD + 2);
        if (!ClientAccess.enabled()) {
            small(g, tr("screen.bsp_teleport.shop.access_off"), left + PAD, y + 4, MUTED);
            return;
        }
        List<Integer> shown = new ArrayList<>();
        for (int i = 0; i < view.entries().size(); i++) {
            ShopEntry e = view.entries().get(i);
            if (assignment(e) != null && tabOf(e) == tab && (e.rent() || e.full())) {
                shown.add(i);
            }
        }
        if (shown.isEmpty()) {
            small(g, tr("screen.bsp_teleport.shop.empty_tab"), left + PAD, y + 4, MUTED);
            return;
        }
        int cw = (W - 2 * PAD - (CARD_COLS - 1) * GAP) / CARD_COLS, bottom = top + H - 14;
        int rows = Math.max(1, (bottom - y) / (CARD_H + GAP));
        scroll = Math.max(0, Math.min(scroll, (shown.size() - 1) / CARD_COLS - rows + 1));
        for (int n = scroll * CARD_COLS; n < shown.size() && n < (scroll + rows) * CARD_COLS; n++) {
            int col = n % CARD_COLS, row = n / CARD_COLS - scroll;
            card(g, shown.get(n), left + PAD + col * (cw + GAP), y + row * (CARD_H + GAP), cw);
        }
    }

    private int drawTabs(GuiGraphics g, int y) {
        int tw = 60;
        for (int i = 0; i < ShopTab.values().length; i++) {
            ShopTab t = ShopTab.values()[i];
            int x = left + PAD + i * (tw + 3);
            boolean on = t == tab;
            g.fill(x, y, x + tw, y + 12, on ? TQ : DIM);
            g.fill(x + 1, y + 1, x + tw - 1, y + 11, SLOT);
            String label = tr("screen.bsp_teleport.shop.tab." + t.name().toLowerCase());
            small(g, label.toUpperCase(), x + (tw - (int) (font.width(label.toUpperCase()) * 0.75f)) / 2, y + 3, on ? TQ : MUTED);
            hits.add(new Hit(x, y, tw, 12, () -> {
                tab = t;
                scroll = 0;
                select(-1);
            }));
        }
        return y + 16;
    }

    private void card(GuiGraphics g, int index, int x, int y, int w) {
        ShopEntry e = view.entries().get(index);
        TokenAssignment t = assignment(e);
        g.fill(x, y, x + w, y + CARD_H, DIM);
        g.fill(x + 1, y + 1, x + w - 1, y + CARD_H - 1, SLOT);
        g.renderItem(new ItemStack(ModItems.ACCESS_TOKENS[e.token() - 1].get()), x + 4, y + 4);
        g.drawString(font, clipFull(t.target(), w - 26), x + 23, y + 5, TEXT, false);
        small(g, "Token " + TokenAssignment.roman(e.token()) + "  " + t.kind().name().toLowerCase(), x + 23, y + 14, MUTED);
        ClientAccess.Entry held = held(e);
        if (held != null && held.tier() != AccessGrant.Tier.RENT) {
            small(g, tr(held.tier() == AccessGrant.Tier.MAIN ? "screen.bsp_teleport.shop.owned_main" : "screen.bsp_teleport.shop.owned"), x + 5, y + 30, OK);
            return;
        }
        if (held != null) {
            small(g, clip(tr("screen.bsp_teleport.shop.rent_left", AccessGrant.describeLeft(held.remainingMs())), w - 8), x + 5, y + 22, GOLD);
        }
        boolean full = fullPick.getOrDefault(index, !e.rent());
        if (!e.sells(full)) {
            full = !full;
        }
        int half = (w - 10 - 2) / 2;
        boolean chosen = full;
        tierKey(g, x + 5, y + 31, half, tr("screen.bsp_teleport.shop.rent"), !chosen, e.rent(), () -> fullPick.put(index, false));
        tierKey(g, x + 5 + half + 2, y + 31, half, tr("screen.bsp_teleport.shop.full"), chosen, e.full(), () -> fullPick.put(index, true));
        boolean affords = affords(e, chosen);
        String p = price(e.price(chosen), e.pieces(chosen)) + (chosen ? "" : "  " + tr("screen.bsp_teleport.shop.hours", e.rentHours()));
        small(g, clip(p, w - 8), x + 5, y + 43, affords ? GOLD : BAD);
        String buy = tr("screen.bsp_teleport.shop.buy");
        key(g, x + w - 4, y + CARD_H - 14, buy, affords ? GOLD : DIM, affords ? GOLD : MUTED, affords ? () -> ask = new Ask(index, e.token(), e.price(chosen), chosen, question(e, chosen)) : null);
    }

    private void tierKey(GuiGraphics g, int x, int y, int w, String label, boolean on, boolean sold, Runnable pick) {
        g.fill(x, y, x + w, y + 10, on && sold ? TQ : DIM);
        g.fill(x + 1, y + 1, x + w - 1, y + 9, SLOT);
        small(g, label.toUpperCase(), x + (w - (int) (font.width(label.toUpperCase()) * 0.75f)) / 2, y + 2, !sold ? DIM : on ? TQ : MUTED);
        if (sold) {
            hits.add(new Hit(x, y, w, 10, pick));
        }
    }

    private String question(ShopEntry e, boolean full) {
        TokenAssignment t = assignment(e);
        String what = t == null ? "?" : t.target();
        String cost = e.price(full) <= 0 ? tr("screen.bsp_teleport.shop.free").toLowerCase()
                : String.format("%,d", e.price(full)) + " " + tr(e.pieces(full) ? "screen.bsp_teleport.shop.pieces" : "screen.bsp_teleport.shop.coins");
        return full ? tr("screen.bsp_teleport.shop.ask_full", what, cost) : tr("screen.bsp_teleport.shop.ask_rent", e.rentHours(), what, cost);
    }

    /** D175: the confirm box over the shop; only its keys answer while it is up. */
    private void drawAsk(GuiGraphics g) {
        int cw = 230, ch = 74;
        int cx = left + (W - cw) / 2, cy = top + (H - ch) / 2;
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.fill(left, top, left + W, top + H, 0xA0000000);
        g.fill(cx - 1, cy - 1, cx + cw + 1, cy + ch + 1, GOLD);
        g.fill(cx, cy, cx + cw, cy + ch, BG);
        int ty = cy + 7;
        for (FormattedCharSequence line : font.split(Component.literal(ask.question()), cw - 14)) {
            if (ty > cy + 27) {
                break;
            }
            g.drawString(font, line, cx + 7, ty, TEXT, false);
            ty += 10;
        }
        small(g, clip(tr("screen.bsp_teleport.shop.ask_hint"), cw - 14), cx + 7, cy + 40, MUTED);
        hits.clear();
        Ask a = ask;
        int bx = key(g, cx + cw - 7, cy + ch - 17, tr("screen.bsp_teleport.shop.cancel"), DIM, MUTED, () -> ask = null) - 4;
        bx = key(g, bx, cy + ch - 17, tr("screen.bsp_teleport.shop.take"), GOLD, GOLD, () -> {
            ask = null;
            BandPackets.sendShopBuy(view.pos(), a.index(), a.token(), a.price(), a.full(), true);
        }) - 4;
        key(g, bx, cy + ch - 17, tr("screen.bsp_teleport.shop.use_now"), OK, OK, () -> {
            ask = null;
            BandPackets.sendShopBuy(view.pos(), a.index(), a.token(), a.price(), a.full(), false);
        });
        g.pose().popPose();
    }

    // Settings (admins)

    private int editorTop() {
        return top + HEAD + 2 + 16 + 16 + 16 + 2 * (SLOT_H + GAP) + 2;
    }

    private void drawSettings(GuiGraphics g) {
        g.drawString(font, tr("screen.bsp_teleport.shop.settings_title"), left + PAD, top + 8, VIO, false);
        int kx = key(g, left + W - PAD, top + 6, tr("screen.bsp_teleport.shop.save"), OK, OK,
                () -> BandPackets.sendShopSave(view.pos(), nameBox.getValue(), messageBox.getValue(), "", edit)) - 4;
        key(g, kx, top + 6, tr("screen.bsp_teleport.shop.back"), DIM, MUTED, () -> {
            settings = false;
            edit = new ArrayList<>(view.entries());
            scroll = 0;
            note = "";
            select(-1);
        });
        int y = top + HEAD + 2;
        small(g, tr("screen.bsp_teleport.shop.name"), left + PAD, y + 5, MUTED);
        small(g, tr("screen.bsp_teleport.shop.message"), left + PAD + 112, y + 5, MUTED);
        y += 16;
        small(g, tr("screen.bsp_teleport.shop.copy_from"), left + PAD, y + 3, MUTED);
        if (view.sources().isEmpty()) {
            small(g, tr("screen.bsp_teleport.shop.no_sources"), left + PAD + 52, y + 3, DIM);
        } else {
            source = Math.floorMod(source, view.sources().size());
            ShopView.Source s = view.sources().get(source);
            int cx = key(g, left + W - PAD, y, tr("screen.bsp_teleport.shop.copy"), VIO, VIO, () -> BandPackets.sendShopSave(view.pos(), "", "", s.key(), List.of())) - 4;
            cx = key(g, cx, y, ">", DIM, MUTED, () -> source++) - 2;
            int lx = left + PAD + 64;
            key(g, lx, y, "<", DIM, MUTED, () -> source--);
            small(g, clip(s.label(), cx - lx - 8), lx + 4, y + 3, TEXT);
        }
        y = drawTabs(g, y + 16);
        drawSlots(g, y);
        drawEditor(g, editorTop());
    }

    /** This tab's entries of the edited list, as indices into it. */
    private List<Integer> tabEntries() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < edit.size(); i++) {
            if (tabOf(edit.get(i)) == tab) {
                out.add(i);
            }
        }
        return out;
    }

    private void drawSlots(GuiGraphics g, int y) {
        List<Integer> mine = tabEntries();
        int sw = (W - 2 * PAD - (SLOT_COLS - 1) * GAP) / SLOT_COLS;
        int total = mine.size() + (edit.size() < AccessShopBlockEntity.ENTRIES_MAX ? 1 : 0);
        scroll = Math.max(0, Math.min(scroll, (total - 1) / SLOT_COLS - 1));
        for (int n = scroll * SLOT_COLS; n < total && n < (scroll + 2) * SLOT_COLS; n++) {
            int x = left + PAD + (n % SLOT_COLS) * (sw + GAP), sy = y + (n / SLOT_COLS - scroll) * (SLOT_H + GAP);
            if (n == mine.size()) {
                g.fill(x, sy, x + sw, sy + SLOT_H, DIM);
                g.fill(x + 1, sy + 1, x + sw - 1, sy + SLOT_H - 1, SLOT);
                String add = tr("screen.bsp_teleport.shop.add");
                small(g, add, x + (sw - (int) (font.width(add) * 0.75f)) / 2, sy + 10, TQ);
                hits.add(new Hit(x, sy, sw, SLOT_H, this::addEntry));
                continue;
            }
            int index = mine.get(n);
            ShopEntry e = edit.get(index);
            TokenAssignment t = assignment(e);
            g.fill(x, sy, x + sw, sy + SLOT_H, index == selected ? TQ : DIM);
            g.fill(x + 1, sy + 1, x + sw - 1, sy + SLOT_H - 1, SLOT);
            g.renderItem(new ItemStack(ModItems.ACCESS_TOKENS[e.token() - 1].get()), x + 3, sy + 5);
            small(g, clip(t == null ? tr("screen.bsp_teleport.shop.unassigned") : t.target(), sw - 24), x + 21, sy + 5, t == null ? BAD : TEXT);
            small(g, clip(summary(e), sw - 24), x + 21, sy + 15, MUTED);
            hits.add(new Hit(x, sy, sw, SLOT_H, () -> select(index)));
        }
    }

    private static String summary(ShopEntry e) {
        String r = e.rent() ? price(e.rentPrice(), e.rentPieces()) + "/" + e.rentHours() + "h" : "";
        String f = e.full() ? price(e.fullPrice(), e.fullPieces()) : "";
        return r.isEmpty() ? f.isEmpty() ? "-" : f : f.isEmpty() ? r : r + " " + f;
    }

    private void drawEditor(GuiGraphics g, int y) {
        if (selected < 0 || selected >= edit.size()) {
            small(g, tr("screen.bsp_teleport.shop.pick_slot"), left + PAD, y + 4, MUTED);
            return;
        }
        ShopEntry e = edit.get(selected);
        TokenAssignment t = assignment(e);
        // line 1: the token (cycling through this tab's assigned tokens), moving and removing
        int x = left + PAD;
        x = key(g, x + keyWidth("<"), y, "<", DIM, MUTED, () -> cycleToken(-1)) + keyWidth("<") + 4;
        String what = TokenAssignment.roman(e.token()) + "  " + (t == null ? tr("screen.bsp_teleport.shop.unassigned") : t.target());
        small(g, clip(what, 110), x, y + 3, t == null ? BAD : TEXT);
        key(g, x + 116 + keyWidth(">"), y, ">", DIM, MUTED, () -> cycleToken(1));
        int rx = key(g, left + W - PAD, y, tr("screen.bsp_teleport.shop.remove"), BAD, BAD, this::removeEntry) - 4;
        rx = key(g, rx, y, tr("screen.bsp_teleport.shop.later"), DIM, MUTED, () -> moveEntry(1)) - 2;
        key(g, rx, y, tr("screen.bsp_teleport.shop.earlier"), DIM, MUTED, () -> moveEntry(-1));
        // line 2: rent on or off, its hours, price and currency
        int ly = y + 17;
        onOff(g, left + PAD, ly, tr("screen.bsp_teleport.shop.rent"), e.rent(),
                () -> change(c -> new ShopEntry(c.token(), !c.rent(), c.rentHours(), c.rentPrice(), c.rentPieces(), c.full(), c.fullPrice(), c.fullPieces())));
        small(g, tr("screen.bsp_teleport.shop.hours_label"), left + PAD + 50, ly + 3, MUTED);
        small(g, tr("screen.bsp_teleport.shop.price"), left + PAD + 116, ly + 3, MUTED);
        currency(g, left + W - PAD, ly, e.rentPieces(),
                () -> change(c -> new ShopEntry(c.token(), c.rent(), c.rentHours(), c.rentPrice(), !c.rentPieces(), c.full(), c.fullPrice(), c.fullPieces())));
        // line 3: full access on or off, its price and currency
        ly += 17;
        onOff(g, left + PAD, ly, tr("screen.bsp_teleport.shop.full"), e.full(),
                () -> change(c -> new ShopEntry(c.token(), c.rent(), c.rentHours(), c.rentPrice(), c.rentPieces(), !c.full(), c.fullPrice(), c.fullPieces())));
        small(g, tr("screen.bsp_teleport.shop.price"), left + PAD + 116, ly + 3, MUTED);
        currency(g, left + W - PAD, ly, e.fullPieces(),
                () -> change(c -> new ShopEntry(c.token(), c.rent(), c.rentHours(), c.rentPrice(), c.rentPieces(), c.full(), c.fullPrice(), !c.fullPieces())));
    }

    private void onOff(GuiGraphics g, int x, int y, String label, boolean on, Runnable flip) {
        String text = label.toUpperCase() + " " + tr(on ? "screen.bsp_teleport.shop.on" : "screen.bsp_teleport.shop.off");
        key(g, x + keyWidth(text), y, text, on ? TQ : DIM, on ? TQ : MUTED, flip);
    }

    private void currency(GuiGraphics g, int right, int y, boolean pieces, Runnable flip) {
        key(g, right, y, tr(pieces ? "screen.bsp_teleport.shop.pieces" : "screen.bsp_teleport.shop.coins"), GOLD, GOLD, flip);
    }

    /** The tokens assigned to what this tab sells, in number order. */
    private List<Integer> tabTokens() {
        List<Integer> out = new ArrayList<>();
        for (int n = 1; n <= TokenAssignment.COUNT; n++) {
            TokenAssignment t = ClientAccess.token(n);
            if (t != null && ShopTab.of(t.kind()) == tab) {
                out.add(n);
            }
        }
        return out;
    }

    private void addEntry() {
        List<Integer> tokens = tabTokens();
        if (tokens.isEmpty()) {
            note = tr("screen.bsp_teleport.shop.no_tokens", tr("screen.bsp_teleport.shop.tab." + tab.name().toLowerCase()).toLowerCase());
            return;
        }
        int token = tokens.get(0);
        for (int n : tokens) {
            int pick = n;
            if (edit.stream().noneMatch(e -> e.token() == pick)) {
                token = n;
                break;
            }
        }
        edit.add(ShopEntry.fresh(token));
        note = "";
        select(edit.size() - 1);
    }

    private void cycleToken(int step) {
        List<Integer> tokens = tabTokens();
        if (tokens.isEmpty() || selected < 0) {
            return;
        }
        int at = tokens.indexOf(edit.get(selected).token());
        int next = tokens.get(Math.floorMod(at + step, tokens.size()));
        change(c -> new ShopEntry(next, c.rent(), c.rentHours(), c.rentPrice(), c.rentPieces(), c.full(), c.fullPrice(), c.fullPieces()));
    }

    /** Swaps the picked entry with the one before or after it on this tab. */
    private void moveEntry(int step) {
        List<Integer> mine = tabEntries();
        int at = mine.indexOf(selected);
        int to = at + step;
        if (at < 0 || to < 0 || to >= mine.size()) {
            return;
        }
        int other = mine.get(to);
        ShopEntry a = edit.get(selected);
        edit.set(selected, edit.get(other));
        edit.set(other, a);
        select(other);
    }

    private void removeEntry() {
        if (selected >= 0 && selected < edit.size()) {
            edit.remove(selected);
            select(-1);
        }
    }

    private void change(java.util.function.UnaryOperator<ShopEntry> how) {
        if (selected >= 0 && selected < edit.size()) {
            edit.set(selected, how.apply(edit.get(selected)).clean());
        }
    }

    /** Picks a slot and fills the hour and price boxes from it. */
    private void select(int index) {
        selected = index;
        if (hoursBox == null) {
            return;
        }
        loading = true;
        if (index >= 0 && index < edit.size()) {
            ShopEntry e = edit.get(index);
            hoursBox.setValue(String.valueOf(e.rentHours()));
            rentBox.setValue(String.valueOf(e.rentPrice()));
            fullBox.setValue(String.valueOf(e.fullPrice()));
        }
        loading = false;
    }

    // Small parts

    private int keyWidth(String label) {
        return (int) (font.width(label.toUpperCase()) * 0.75f) + 8;
    }

    /** A small right-aligned key; returns its left edge. A null action draws it without a click. */
    private int key(GuiGraphics g, int rightX, int y, String label, int border, int colour, @Nullable Runnable action) {
        int w = keyWidth(label);
        int x = rightX - w;
        g.fill(x, y, x + w, y + 12, border);
        g.fill(x + 1, y + 1, x + w - 1, y + 11, SLOT);
        small(g, label.toUpperCase(), x + 4, y + 3, colour);
        if (action != null) {
            hits.add(new Hit(x, y, w, 12, action));
        }
        return x;
    }

    private String clip(String text, int maxWidth) {
        if (font.width(text) * 0.75f <= maxWidth) {
            return text;
        }
        while (text.length() > 1 && font.width(text + "...") * 0.75f > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "...";
    }

    /** {@link #clip} for text drawn at the full font size. */
    private String clipFull(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        while (text.length() > 1 && font.width(text + "...") > maxWidth) {
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

    // Input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        mx /= scale;
        my /= scale;
        if (button == 0) {
            for (Hit hit : List.copyOf(hits)) {
                if (hit.contains(mx, my)) {
                    hit.action().run();
                    return true;
                }
            }
        }
        if (ask != null) {
            return true; // a click beside the box does nothing
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
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (ask == null) {
            scroll = Math.max(0, scroll - (int) Math.signum(delta));
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (ask != null) {
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                ask = null; // Esc answers CANCEL and leaves the shop open
            }
            return true; // never a purchase by keyboard
        }
        boolean typing = getFocused() instanceof EditBox box && box.isVisible();
        if (!typing && minecraft.options.keyInventory.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}
