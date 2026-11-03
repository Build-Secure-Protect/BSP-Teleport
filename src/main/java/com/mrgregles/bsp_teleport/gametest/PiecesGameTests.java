package com.mrgregles.bsp_teleport.gametest;

import com.mrgregles.bsp_core.api.BspBank;
import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.access.AccessChecks;
import com.mrgregles.bsp_teleport.access.AccessGrant;
import com.mrgregles.bsp_teleport.access.AccessRule;
import com.mrgregles.bsp_teleport.compat.BspCore;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.data.Warp;
import com.mrgregles.bsp_teleport.teleport.PaidTrips;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * GameTests for D163 and B34: warp costs and PIECES rules paid from the BSP-Core bank, all or nothing; warps saved before
 * D163 still paying in coins; and everything given back when the move does not happen. On a single server the bank
 * answers at once, so each charge has its answer on return.
 */
@GameTestHolder(BSPTeleport.MODID)
@PrefixGameTestTemplate(false)
public class PiecesGameTests {
    private static final String EMPTY = "empty";

    /** A test player with {@code pieces} in the bank. */
    private static ServerPlayer withPieces(GameTestHelper h, long pieces) {
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        BspBank.credit(h.getLevel().getServer(), player.getUUID(), pieces, "test", "test", ok -> {
        });
        return player;
    }

    @GameTest(template = EMPTY)
    public static void pieces_come_from_the_bank_and_go_back_when_the_move_cannot_go(GameTestHelper h) {
        ServerPlayer player = withPieces(h, 5000);
        long have = BspCore.pieces(player);
        h.assertTrue(have >= 5000, "the test player has pieces: " + have);

        AccessChecks.Result rules = AccessChecks.evaluate(player, List.of(AccessRule.of("PIECES", "", 2000, false)), AccessGrant.Kind.WARP, "test");
        h.assertTrue(rules.ok() && rules.pieces() == 2000 && rules.coins() == 0, "a PIECES rule passes and asks 2000: " + rules);
        AccessChecks.Paid[] paid = {null};
        AccessChecks.charge(player, rules, 0, 500, "test", p -> paid[0] = p);
        h.assertTrue(paid[0] != null && paid[0].pieces() == 2500, "the rule and a 500-piece warp cost are paid together: " + paid[0]);
        h.assertTrue(BspCore.pieces(player) == have - 2500, "2500 pieces were taken: " + BspCore.pieces(player));

        long before = BspCore.pieces(player);
        paid[0] = null;
        AccessChecks.charge(player, new AccessChecks.Result(null, 0, 0, List.of()), 0, (int) before + 1, "test", p -> paid[0] = p);
        h.assertTrue(paid[0] == null, "more than the balance is refused");
        h.assertTrue(BspCore.pieces(player) == before, "and nothing is taken");

        AccessRule pearl = AccessRule.of("ITEM", "minecraft:ender_pearl", 1, true);
        AccessChecks.charge(player, new AccessChecks.Result(null, 0, 1000, List.of(pearl)), 0, 0, "test", p -> paid[0] = p);
        h.assertTrue(paid[0] == null, "an item to use up that is gone by the bank's answer refuses the move");
        h.assertTrue(BspCore.pieces(player) == before, "and the pieces are given back: " + BspCore.pieces(player));

        AccessChecks.Result huge = AccessChecks.evaluate(player, List.of(AccessRule.of("PIECES", "", Integer.MAX_VALUE, false),
                AccessRule.of("PIECES", "", Integer.MAX_VALUE, false)), AccessGrant.Kind.WARP, "test");
        h.assertFalse(huge.ok(), "two huge prices add up to more than anyone holds, not to a negative (free) price");
        h.assertTrue(AccessChecks.sum(Integer.MAX_VALUE, 5) == Integer.MAX_VALUE, "sums stop at the largest int");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_move_that_fails_gives_back_pieces_coins_and_items(GameTestHelper h) {
        ServerPlayer player = withPieces(h, 5000);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.ENDER_PEARL, 3));
        BspCore.giveCoins(player, 50);
        long pieces = BspCore.pieces(player);
        int coins = BspCore.coins(player);
        h.assertTrue(coins >= 50, "the test player carries coins: " + coins);

        AccessRule pearl = AccessRule.of("ITEM", "minecraft:ender_pearl", 2, true);
        AccessChecks.Result rules = AccessChecks.evaluate(player, List.of(pearl, AccessRule.of("COINS", "", 20, false)), AccessGrant.Kind.PORTAL, "test");
        AccessChecks.Paid[] paid = {null};
        AccessChecks.charge(player, rules, 0, 1000, "test", p -> paid[0] = p);
        h.assertTrue(paid[0] != null, "paid: " + paid[0]);
        h.assertTrue(BspCore.pieces(player) == pieces - 1000 && BspCore.coins(player) == coins - 20 && player.getMainHandItem().getCount() == 1,
                "1000 pieces, 20 coins and 2 pearls were taken");

        AccessChecks.refund(h.getLevel().getServer(), paid[0], player);
        h.assertTrue(BspCore.pieces(player) == pieces, "the pieces are back: " + BspCore.pieces(player));
        h.assertTrue(BspCore.coins(player) == coins, "the coins are back: " + BspCore.coins(player));
        h.assertTrue(player.getInventory().countItem(Items.ENDER_PEARL) == 3, "the pearls are back: " + player.getInventory().countItem(Items.ENDER_PEARL));
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_paid_switch_to_another_server_is_given_back_unless_the_player_left_through_the_proxy(GameTestHelper h) {
        // Left before the proxy was asked (a crash during the hold): the pieces, and the coins as pieces, go to the bank.
        ServerPlayer crashed = withPieces(h, 100);
        long start = BspCore.pieces(crashed);
        PaidTrips.watch(crashed, new AccessChecks.Paid(crashed.getUUID(), "test", 3, 400, List.of()), "BSP-Elsewhere", null);
        PaidTrips.onLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(crashed));
        h.assertTrue(BspCore.pieces(crashed) == start + 400 + BspCore.coinsAsPieces(3), "a crash before the switch gives it all back: " + BspCore.pieces(crashed));

        // Asked, then gone: the move happened, nothing goes back.
        ServerPlayer moved = withPieces(h, 100);
        long movedStart = BspCore.pieces(moved);
        PaidTrips.watch(moved, new AccessChecks.Paid(moved.getUUID(), "test", 0, 400, List.of()), "BSP-Elsewhere", null);
        PaidTrips.asked(moved);
        PaidTrips.onLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(moved));
        h.assertTrue(BspCore.pieces(moved) == movedStart, "a player who left through the proxy keeps nothing back: " + BspCore.pieces(moved));

        // Asked, still here long after: the other server did not take them; it goes back, the cooldown too.
        ServerPlayer stuck = withPieces(h, 100);
        long stuckStart = BspCore.pieces(stuck);
        boolean[] undone = {false};
        PaidTrips.watch(stuck, new AccessChecks.Paid(stuck.getUUID(), "test", 0, 400, List.of()), "BSP-Elsewhere", () -> undone[0] = true);
        PaidTrips.asked(stuck);
        PaidTrips.expireForTest(stuck, 10_000);
        h.assertTrue(PaidTrips.watching(stuck) && BspCore.pieces(stuck) == stuckStart, "ten seconds is not yet a failed switch");
        PaidTrips.expireForTest(stuck, 60_000);
        h.assertFalse(PaidTrips.watching(stuck), "the watch ends");
        h.assertTrue(BspCore.pieces(stuck) == stuckStart + 400 && undone[0], "a switch that never went through gives the pieces and the cooldown back");

        // Audit: asked, still here, and paying for another move (a retry after a failed switch): the first goes back now.
        ServerPlayer retry = withPieces(h, 100);
        long retryStart = BspCore.pieces(retry);
        PaidTrips.watch(retry, new AccessChecks.Paid(retry.getUUID(), "test", 0, 400, List.of()), "BSP-Elsewhere", null);
        PaidTrips.asked(retry);
        PaidTrips.watch(retry, new AccessChecks.Paid(retry.getUUID(), "test", 0, 300, List.of()), "BSP-Elsewhere", null);
        h.assertTrue(BspCore.pieces(retry) == retryStart, "a second move straight after the ask leaves the first as made");
        PaidTrips.asked(retry);
        PaidTrips.agedForTest(retry, 10_000);
        PaidTrips.watch(retry, new AccessChecks.Paid(retry.getUUID(), "test", 0, 200, List.of()), "BSP-Elsewhere", null);
        h.assertTrue(BspCore.pieces(retry) == retryStart + 300, "a retry ten seconds after the ask gives the first back: " + BspCore.pieces(retry));
        PaidTrips.clear();
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void tpnow_takes_its_price_only_with_a_move_that_happens(GameTestHelper h) {
        int oldPrice = com.mrgregles.bsp_teleport.TeleportConfig.SKIP_COOLDOWN_COINS.get();
        int oldWarmup = com.mrgregles.bsp_teleport.TeleportConfig.WARMUP_SECONDS.get();
        try {
            com.mrgregles.bsp_teleport.TeleportConfig.SKIP_COOLDOWN_COINS.set(5);
            com.mrgregles.bsp_teleport.TeleportConfig.WARMUP_SECONDS.set(0);
            ServerPlayer player = TestPlayers.mockServerPlayer(h);
            BspCore.giveCoins(player, 20);
            int coins = BspCore.coins(player);
            String world = h.getLevel().dimension().location().toString();
            com.mrgregles.bsp_teleport.teleport.Teleports.restore(player.getUUID(), System.currentTimeMillis() + 60_000, null);

            // A teleport whose landing world does not exist: refused before any price is taken.
            com.mrgregles.bsp_teleport.api.Destination nowhere = new com.mrgregles.bsp_teleport.api.Destination("", "bsp_teleport:no_such_world", 5.5, 90, 5.5, "");
            h.assertFalse(com.mrgregles.bsp_teleport.teleport.Teleports.start(player, nowhere, net.minecraft.network.chat.Component.literal("x"),
                    com.mrgregles.bsp_teleport.teleport.TeleportKind.HOME, null), "the cooldown refuses it first");
            h.assertFalse(com.mrgregles.bsp_teleport.teleport.Teleports.payAndRetry(player), "then the missing world refuses it");
            h.assertTrue(BspCore.coins(player) == coins, "and /tpnow took nothing: " + BspCore.coins(player));
            h.assertTrue(com.mrgregles.bsp_teleport.teleport.Teleports.cooldownLeft(player) > 0, "the cooldown is still there");

            // A teleport that happens: the price is taken with it.
            com.mrgregles.bsp_teleport.api.Destination there = new com.mrgregles.bsp_teleport.api.Destination("", world, 30.5, 90, 30.5, "");
            com.mrgregles.bsp_teleport.teleport.Teleports.start(player, there, net.minecraft.network.chat.Component.literal("x"),
                    com.mrgregles.bsp_teleport.teleport.TeleportKind.HOME, null);
            h.assertTrue(com.mrgregles.bsp_teleport.teleport.Teleports.payAndRetry(player), "/tpnow starts it");
            h.assertTrue(BspCore.coins(player) == coins - 5, "and takes the price: " + BspCore.coins(player));
            h.assertTrue(Math.abs(player.getX() - 30.5) < 1 && Math.abs(player.getZ() - 30.5) < 1, "with the move: " + player.position());

            // B37: a refused /rtp keeps its search; /tpnow runs it, not a paid move to where the player stands.
            com.mrgregles.bsp_teleport.teleport.Teleports.restore(player.getUUID(), System.currentTimeMillis() + 60_000, null);
            int beforeRtp = BspCore.coins(player);
            int[] searches = {0};
            com.mrgregles.bsp_teleport.api.Destination found = new com.mrgregles.bsp_teleport.api.Destination("", world, -40.5, 90, 12.5, "");
            java.util.function.BooleanSupplier search = () -> {
                searches[0]++;
                return com.mrgregles.bsp_teleport.teleport.Teleports.start(player, found, net.minecraft.network.chat.Component.literal("random"),
                        com.mrgregles.bsp_teleport.teleport.TeleportKind.RANDOM, null);
            };
            h.assertFalse(com.mrgregles.bsp_teleport.teleport.Teleports.refuseRandom(player, search), "the cooldown refuses the /rtp");
            h.assertTrue(searches[0] == 0, "before any search");
            h.assertTrue(com.mrgregles.bsp_teleport.teleport.Teleports.payAndRetry(player), "/tpnow searches and starts the move");
            h.assertTrue(searches[0] == 1, "the search ran once: " + searches[0]);
            h.assertTrue(Math.abs(player.getX() + 40.5) < 1 && Math.abs(player.getZ() - 12.5) < 1, "to the spot it found: " + player.position());
            h.assertTrue(BspCore.coins(player) == beforeRtp - 5, "and the price went with it: " + BspCore.coins(player));
            h.succeed();
        } finally {
            com.mrgregles.bsp_teleport.TeleportConfig.SKIP_COOLDOWN_COINS.set(oldPrice);
            com.mrgregles.bsp_teleport.TeleportConfig.WARMUP_SECONDS.set(oldWarmup);
        }
    }

    @GameTest(template = EMPTY)
    public static void a_warp_saved_before_pieces_pays_in_coins_and_a_new_one_in_pieces(GameTestHelper h) {
        Position at = new Position("BSP-Test", "minecraft:overworld", 0, 64, 0, 0f, 0f);
        Warp fresh = new Warp("market", at, 250, false);
        h.assertTrue(fresh.pieces(), "a new warp starts in pieces");
        h.assertTrue(Warp.load(fresh.save()).pieces(), "and keeps it when saved and loaded");

        CompoundTag old = fresh.withCost(250, false).save();
        old.remove("CostPieces");
        Warp loaded = Warp.load(old);
        h.assertFalse(loaded.pieces(), "a warp saved before D163 loads as coins");
        h.assertTrue(loaded.cost() == 250, "with its cost: " + loaded.cost());
        h.succeed();
    }
}
