package com.mrgregles.bsp_teleport.access;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.compat.BspCore;
import com.mrgregles.bsp_teleport.perm.Permissions;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Runs a rule list (D86) for a player: the checks first, then, once the move is certain, the charges. */
public final class AccessChecks {
    /** @param refusal the line for the player, or null when every rule passed; a TOKEN refusal also opens the popup */
    /** @param pieces what the PIECES rules (D163) take from the bank */
    public record Result(@Nullable Component refusal, int coins, int pieces, List<AccessRule> consumed) {
        public boolean ok() {
            return refusal == null;
        }
    }

    /**
     * B34: what a move took, kept until the move has happened so all of it can be given back when it does not.
     *
     * @param items   copies of the stacks used up
     * @param payment SC-1: how the pieces were paid (store credit and/or bank), so a refund puts credit back on its
     *                ticket; null for pieces from the bank alone
     */
    public record Paid(UUID player, String reason, int coins, int pieces, List<ItemStack> items, @Nullable BspCore.Payment payment) {
        /** Pieces from the bank alone. */
        public Paid(UUID player, String reason, int coins, int pieces, List<ItemStack> items) {
            this(player, reason, coins, pieces, items, null);
        }

        public boolean nothing() {
            return coins <= 0 && pieces <= 0 && items.isEmpty();
        }

        /** The same payment with {@code more} coins taken on top ({@code /tpnow}'s price). */
        public Paid plusCoins(int more) {
            return new Paid(player, reason, sum(coins, more), pieces, items, payment);
        }
    }

    private AccessChecks() {
    }

    /** Admins pass every rule. */
    public static Result evaluate(ServerPlayer player, List<AccessRule> rules, AccessGrant.Kind what, String target) {
        if (rules.isEmpty() || Permissions.isAdmin(player)) {
            return new Result(null, 0, 0, List.of());
        }
        int coins = 0, pieces = 0;
        List<AccessRule> consumed = new java.util.ArrayList<>();
        for (AccessRule r : rules) {
            switch (r.kind()) {
                case RANK -> {
                    if (!Permissions.has(player, "group." + r.value())) {
                        return new Result(msg("access.rule_rank", r.value()), 0, 0, List.of());
                    }
                }
                case NODE -> {
                    if (!Permissions.has(player, r.value())) {
                        return new Result(msg("access.rule_node", r.value()), 0, 0, List.of());
                    }
                }
                case ITEM -> {
                    Item item = itemOf(r.value());
                    int n = Math.max(1, r.count());
                    if (item == null || held(player, item) < n) {
                        return new Result(msg("access.rule_item", item == null ? r.value() : new ItemStack(item).getHoverName().getString(), n), 0, 0, List.of());
                    }
                    if (r.consume()) {
                        consumed.add(r);
                    }
                }
                case COINS -> {
                    coins = sum(coins, r.count());
                    if (BspCore.coins(player) < coins) {
                        return new Result(msg("access.rule_coins", coins), 0, 0, List.of());
                    }
                }
                case PIECES -> {
                    pieces = sum(pieces, r.count());
                    if (BspCore.teleportPieces(player) < pieces) { // SC-1: the bank plus teleport store credit
                        return new Result(msg("access.rule_pieces", BspCore.piecesText(pieces)), 0, 0, List.of());
                    }
                }
                case TIME -> {
                    Component closed = closedLine(r, OpenHours.now());
                    if (closed != null) {
                        return new Result(closed, 0, 0, List.of());
                    }
                }
                case TOKEN -> {
                    if (!AccessService.enabled() || !AccessRules.has(AccessService.grantsOf(player.server, player.getUUID()), what, target, System.currentTimeMillis())) {
                        AccessService.deny(player, what, target);
                        return new Result(Component.empty(), 0, 0, List.of());
                    }
                }
            }
        }
        return new Result(null, coins, pieces, consumed);
    }

    /** TIME (T82): "Closed now. Opens at 03:00 (Europe/London).", or null when open at {@code minute} (or the hours are unreadable). */
    @Nullable
    static Component closedLine(AccessRule rule, int minute) {
        OpenHours hours = rule.hours();
        if (hours == null || hours.isOpen(minute)) {
            return null;
        }
        return msg("access.rule_time", OpenHours.hhmm(hours.nextOpen(minute)), OpenHours.zone().getId());
    }

    /**
     * The TIME rules alone (D155): why the place is shut for this player now, or null. A portal entry leading to a warp
     * checks only this, as its other rules were never applied through portals. Admins pass.
     */
    @Nullable
    public static Component closedNow(ServerPlayer player, List<AccessRule> rules) {
        if (Permissions.isAdmin(player)) {
            return null;
        }
        int now = OpenHours.now();
        for (AccessRule r : rules) {
            Component closed = r.kind() == AccessRule.Kind.TIME ? closedLine(r, now) : null;
            if (closed != null) {
                return closed;
            }
        }
        return null;
    }

    /** For the band (D150): -1 with no TIME rule, -2 when open now, else the minute it next opens. */
    public static int opensAt(List<AccessRule> rules) {
        int now = OpenHours.now();
        for (AccessRule r : rules) {
            OpenHours hours = r.hours();
            if (hours != null) {
                return hours.isOpen(now) ? -2 : hours.nextOpen(now);
            }
        }
        return -1;
    }

    /**
     * Takes what the move costs: the rules' charges plus {@code extraCoins} and {@code extraPieces} (a warp's own cost),
     * all or nothing. {@code then} gets what was paid once everything is, on the server thread, or null when nothing was
     * taken; the caller moves the player there and then, and gives it back with {@link #refund} when the move fails (B34).
     * The bank answers later on a network (D163), so the pieces go first and are given back when the coins or items are
     * gone by then, or the player died or left meanwhile.
     */
    public static void charge(ServerPlayer player, Result result, int extraCoins, int extraPieces, String reason, Consumer<Paid> then) {
        int coins = sum(result.coins(), extraCoins);
        int pieces = sum(result.pieces(), extraPieces);
        if (coins > 0 && BspCore.coins(player) < coins) {
            player.displayClientMessage(msg("access.rule_coins", coins), false);
            then.accept(null);
            return;
        }
        if (pieces > 0 && BspCore.teleportPieces(player) < pieces) { // SC-1: the bank plus teleport store credit
            player.displayClientMessage(msg("access.rule_pieces", BspCore.piecesText(pieces)), false);
            then.accept(null);
            return;
        }
        // SC-1: store credit first, then the bank, all or nothing; on an older BSP-Core the bank alone, as before.
        BspCore.payTeleportPieces(player, pieces, reason, payment -> {
            if (payment == null) {
                // Short, or the bank's database did not answer: either way nothing was taken.
                player.displayClientMessage(msg("access.bank_refused", BspCore.piecesText(pieces)), false);
                then.accept(null);
                return;
            }
            Component missing = player.hasDisconnected() || player.isRemoved() || player.isDeadOrDying() ? Component.empty() : missingItem(player, result);
            if (missing == null && coins > 0 && !BspCore.pay(player, coins)) {
                missing = msg("access.rule_coins", coins);
            }
            if (missing != null) {
                BspCore.refundTeleportPieces(player.server, player.getUUID(), payment, reason + " (not moved)");
                if (!missing.getString().isEmpty()) {
                    player.displayClientMessage(missing, false);
                }
                then.accept(null);
                return;
            }
            List<ItemStack> taken = new java.util.ArrayList<>();
            for (AccessRule r : result.consumed()) {
                Item item = itemOf(r.value());
                if (item != null) {
                    taken.addAll(take(player, item, Math.max(1, r.count())));
                }
            }
            if (coins > 0) {
                player.displayClientMessage(msg("access.rule_paid", coins), false);
            }
            if (pieces > 0) {
                player.displayClientMessage(paidLine(payment), false);
            }
            then.accept(new Paid(player.getUUID(), reason, coins, pieces, taken, payment.receipt() == null ? null : payment));
        });
    }

    /**
     * SC-1: what a pieces price took, in players' words: "Paid 500 pieces from your bank.", "Paid 500 pieces with store
     * credit (Teleport Pass: 1,000 pieces left).", or both parts when it split.
     */
    static Component paidLine(BspCore.Payment p) {
        if (p.credit() <= 0) {
            return msg("access.rule_paid_pieces", BspCore.piecesText(p.bank()));
        }
        String credit = BspCore.piecesText(p.credit()), left = BspCore.piecesText(p.creditLeft());
        if (p.bank() <= 0) {
            return p.ticketSpent() ? msg("access.paid_credit_spent", credit, p.ticketName()) : msg("access.paid_credit", credit, p.ticketName(), left);
        }
        String bank = BspCore.piecesText(p.bank());
        return p.ticketSpent() ? msg("access.paid_split_spent", credit, p.ticketName(), bank) : msg("access.paid_split", credit, p.ticketName(), left, bank);
    }

    /**
     * B34: gives back everything a move took, because the move did not happen. Pieces go to the bank, and store credit back
     * to its ticket (SC-1); both work offline.
     * Coins and items go into {@code online}'s inventory (dropped at their feet when full); with the player gone, the
     * coins go to the bank as pieces at its rate and the items wait in {@link #OWED} for their next login on this server.
     */
    public static void refund(MinecraftServer server, @Nullable Paid paid, @Nullable ServerPlayer online) {
        if (paid == null || paid.nothing()) {
            return;
        }
        String reason = paid.reason() + " (not moved)";
        // SC-1: pieces paid partly with store credit go back through the receipt (credit to its ticket, the rest to the bank).
        boolean byReceipt = paid.payment() != null && paid.payment().receipt() != null;
        long toBank = byReceipt ? 0 : paid.pieces();
        boolean here = online != null && !online.hasDisconnected() && !online.isRemoved();
        if (here) {
            BspCore.giveCoins(online, paid.coins());
            for (ItemStack s : paid.items()) {
                give(online, s.copy());
            }
            online.displayClientMessage(msg("access.refunded"), false);
        } else {
            toBank += BspCore.coinsAsPieces(paid.coins());
            if (!paid.items().isEmpty()) {
                OWED.computeIfAbsent(paid.player(), k -> new java.util.ArrayList<>()).addAll(paid.items());
            }
        }
        if (byReceipt) {
            BspCore.refundTeleportPieces(server, paid.player(), paid.payment(), reason);
        }
        BspCore.refundPieces(server, paid.player(), toBank, reason);
        BSPTeleport.LOGGER.info("Gave back what {} paid for '{}', the move did not happen: {} pieces, {} coins{}, {} item stacks{}",
                paid.player(), paid.reason(), paid.pieces(), paid.coins(), here ? "" : " (as pieces, player gone)", paid.items().size(),
                here || paid.items().isEmpty() ? "" : " (kept for their next login here)");
    }

    /** B34: items owed to players who were gone when their move failed, given at their next login on this server. */
    private static final java.util.Map<UUID, List<ItemStack>> OWED = new java.util.HashMap<>();

    public static void giveOwed(ServerPlayer player) {
        List<ItemStack> owed = OWED.remove(player.getUUID());
        if (owed != null) {
            owed.forEach(s -> give(player, s.copy()));
            player.displayClientMessage(msg("access.refunded"), false);
        }
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack) && !stack.isEmpty()) {
            player.drop(stack, false);
        }
    }

    /** Two prices added, never past {@code Integer.MAX_VALUE}: a wrapped sum would go below 0 and charge nothing. */
    public static int sum(int a, int b) {
        return (int) Math.min(Integer.MAX_VALUE, (long) Math.max(0, a) + Math.max(0, b));
    }

    /** The refusal for an item to be used up that the player no longer holds enough of, or null when all are there. */
    @Nullable
    private static Component missingItem(ServerPlayer player, Result result) {
        for (AccessRule r : result.consumed()) {
            Item item = itemOf(r.value());
            int n = Math.max(1, r.count());
            if (item == null || held(player, item) < n) {
                return msg("access.rule_item", item == null ? r.value() : new ItemStack(item).getHoverName().getString(), n);
            }
        }
        return null;
    }

    @Nullable
    private static Item itemOf(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        Item item = key == null ? null : ForgeRegistries.ITEMS.getValue(key);
        return item == null || item == net.minecraft.world.item.Items.AIR ? null : item;
    }

    /** How many of the item the player holds in either hand. */
    private static int held(ServerPlayer player, Item item) {
        int n = 0;
        for (ItemStack s : new ItemStack[]{player.getMainHandItem(), player.getOffhandItem()}) {
            if (s.is(item)) {
                n += s.getCount();
            }
        }
        return n;
    }

    /** Takes {@code count} of the item from the hands; copies of what was taken, for a refund. */
    private static List<ItemStack> take(ServerPlayer player, Item item, int count) {
        List<ItemStack> taken = new java.util.ArrayList<>();
        for (ItemStack s : new ItemStack[]{player.getMainHandItem(), player.getOffhandItem()}) {
            if (count > 0 && s.is(item)) {
                int take = Math.min(count, s.getCount());
                taken.add(s.copyWithCount(take));
                s.shrink(take);
                count -= take;
            }
        }
        return taken;
    }

    private static Component msg(String key, Object... args) {
        return Component.translatable("message.bsp_teleport." + key, args);
    }
}
