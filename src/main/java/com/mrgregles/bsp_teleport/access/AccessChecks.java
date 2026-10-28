package com.mrgregles.bsp_teleport.access;

import com.mrgregles.bsp_teleport.compat.BspCore;
import com.mrgregles.bsp_teleport.perm.Permissions;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.List;

/** Runs a rule list (D86) for a player: the checks first, then, once the move is certain, the charges. */
public final class AccessChecks {
    /** @param refusal the line for the player, or null when every rule passed; a TOKEN refusal also opens the popup */
    public record Result(@Nullable Component refusal, int coins, List<AccessRule> consumed) {
        public boolean ok() {
            return refusal == null;
        }
    }

    private AccessChecks() {
    }

    /** Admins pass every rule. */
    public static Result evaluate(ServerPlayer player, List<AccessRule> rules, AccessGrant.Kind what, String target) {
        if (rules.isEmpty() || Permissions.isAdmin(player)) {
            return new Result(null, 0, List.of());
        }
        int coins = 0;
        List<AccessRule> consumed = new java.util.ArrayList<>();
        for (AccessRule r : rules) {
            switch (r.kind()) {
                case RANK -> {
                    if (!Permissions.has(player, "group." + r.value())) {
                        return new Result(msg("access.rule_rank", r.value()), 0, List.of());
                    }
                }
                case NODE -> {
                    if (!Permissions.has(player, r.value())) {
                        return new Result(msg("access.rule_node", r.value()), 0, List.of());
                    }
                }
                case ITEM -> {
                    Item item = itemOf(r.value());
                    int n = Math.max(1, r.count());
                    if (item == null || held(player, item) < n) {
                        return new Result(msg("access.rule_item", item == null ? r.value() : new ItemStack(item).getHoverName().getString(), n), 0, List.of());
                    }
                    if (r.consume()) {
                        consumed.add(r);
                    }
                }
                case COINS -> {
                    coins += r.count();
                    if (BspCore.coins(player) < coins) {
                        return new Result(msg("access.rule_coins", coins), 0, List.of());
                    }
                }
                case TIME -> {
                    Component closed = closedLine(r, OpenHours.now());
                    if (closed != null) {
                        return new Result(closed, 0, List.of());
                    }
                }
                case TOKEN -> {
                    if (!AccessService.enabled() || !AccessRules.has(AccessService.grantsOf(player.server, player.getUUID()), what, target, System.currentTimeMillis())) {
                        AccessService.deny(player, what, target);
                        return new Result(Component.empty(), 0, List.of());
                    }
                }
            }
        }
        return new Result(null, coins, consumed);
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

    /** Pays the coins and uses up the items; false when the coins could not be paid after all. */
    public static boolean charge(ServerPlayer player, Result result) {
        if (result.coins() > 0 && !BspCore.pay(player, result.coins())) {
            player.displayClientMessage(msg("access.rule_coins", result.coins()), false);
            return false;
        }
        for (AccessRule r : result.consumed()) {
            Item item = itemOf(r.value());
            if (item != null) {
                take(player, item, Math.max(1, r.count()));
            }
        }
        if (result.coins() > 0) {
            player.displayClientMessage(msg("access.rule_paid", result.coins()), false);
        }
        return true;
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

    private static void take(ServerPlayer player, Item item, int count) {
        for (ItemStack s : new ItemStack[]{player.getMainHandItem(), player.getOffhandItem()}) {
            if (count > 0 && s.is(item)) {
                int take = Math.min(count, s.getCount());
                s.shrink(take);
                count -= take;
            }
        }
    }

    private static Component msg(String key, Object... args) {
        return Component.translatable("message.bsp_teleport." + key, args);
    }
}
