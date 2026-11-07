package com.mrgregles.bsp_teleport.gametest;

import com.mrgregles.bsp_core.api.BspBank;
import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.access.AccessChecks;
import com.mrgregles.bsp_teleport.access.AccessGrant;
import com.mrgregles.bsp_teleport.access.AccessRule;
import com.mrgregles.bsp_teleport.compat.BspCore;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * BSP-Core SC-1: a store credit ticket marked for teleports pays pieces prices before the bank, and a move that does not
 * happen puts the credit back on the ticket rather than into the bank. On a single server the bank and the tickets answer
 * at once, so each charge has its answer on return.
 */
@GameTestHolder(BSPTeleport.MODID)
@PrefixGameTestTemplate(false)
public class StoreCreditGameTests {
    private static final String EMPTY = "empty";

    private static ServerPlayer withPieces(GameTestHelper h, long pieces) {
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        if (pieces > 0) {
            BspBank.credit(h.getLevel().getServer(), player.getUUID(), pieces, "test", "test", ok -> {
            });
        }
        return player;
    }

    /** A teleport ticket worth {@code pieces} in the inventory (BSP-Core moves the stack in, so the one it returns is empty). */
    private static void ticket(GameTestHelper h, ServerPlayer player, long pieces) {
        long before = BspCore.teleportPieces(player);
        BspCore.testTeleportTicket(player, pieces);
        h.assertTrue(BspCore.teleportPieces(player) == before + pieces, "the ticket adds " + pieces + " to what the player can pay with: "
                + before + " -> " + BspCore.teleportPieces(player));
    }

    @GameTest(template = EMPTY)
    public static void credit_pays_first_and_the_bank_pays_the_rest(GameTestHelper h) {
        ServerPlayer player = withPieces(h, 1000);
        long bank = BspCore.pieces(player);
        ticket(h, player, 500);
        h.assertTrue(BspCore.teleportPieces(player) == bank + 500, "spendable is the bank plus the credit: " + BspCore.teleportPieces(player));

        AccessChecks.Paid[] paid = {null};
        AccessChecks.charge(player, new AccessChecks.Result(null, 0, 0, List.of()), 0, 800, "test warp", p -> paid[0] = p);
        h.assertTrue(paid[0] != null && paid[0].pieces() == 800, "800 pieces paid: " + paid[0]);
        BspCore.Payment payment = paid[0].payment();
        h.assertTrue(payment != null && payment.credit() == 500 && payment.bank() == 300 && payment.ticketSpent(),
                "the ticket's 500 first, then 300 from the bank: " + payment);
        h.assertTrue(BspCore.pieces(player) == bank - 300, "the bank lost 300: " + BspCore.pieces(player));
        h.assertTrue(BspCore.teleportPieces(player) == bank - 300, "and no credit is left: " + BspCore.teleportPieces(player));
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_move_that_fails_puts_the_credit_back_on_the_ticket(GameTestHelper h) {
        ServerPlayer player = withPieces(h, 1000);
        long bank = BspCore.pieces(player);
        ticket(h, player, 500);

        // Paid from the ticket alone, which keeps credit: the refund puts it back on the ticket.
        AccessChecks.Paid[] paid = {null};
        AccessChecks.charge(player, new AccessChecks.Result(null, 0, 0, List.of()), 0, 300, "test warp", p -> paid[0] = p);
        h.assertTrue(paid[0] != null && paid[0].payment() != null && paid[0].payment().credit() == 300 && paid[0].payment().bank() == 0,
                "paid with credit: " + paid[0]);
        AccessChecks.refund(h.getLevel().getServer(), paid[0], player);
        h.assertTrue(BspCore.pieces(player) == bank, "nothing goes into the bank: " + BspCore.pieces(player));
        h.assertTrue(BspCore.teleportPieces(player) == bank + 500, "the credit goes back on the ticket: " + BspCore.teleportPieces(player));
        AccessChecks.refund(h.getLevel().getServer(), paid[0], player);
        h.assertTrue(BspCore.pieces(player) == bank && BspCore.teleportPieces(player) == bank + 500, "a second refund gives nothing more");

        // Split, using the ticket up: the bank part goes back to the bank and none of the credit does. (BSP-Core hands a
        // used-up ticket back to an online player through the player list, which GameTest players are not in, so the
        // ticket's return to the inventory is the owner's in-game check.)
        paid[0] = null;
        AccessChecks.charge(player, new AccessChecks.Result(null, 0, 0, List.of()), 0, 700, "test warp", p -> paid[0] = p);
        h.assertTrue(paid[0] != null && paid[0].payment() != null && paid[0].payment().credit() == 500 && paid[0].payment().bank() == 200,
                "500 from the ticket, 200 from the bank: " + paid[0]);
        AccessChecks.refund(h.getLevel().getServer(), paid[0], player);
        h.assertTrue(BspCore.pieces(player) == bank, "only the bank's 200 goes back to the bank: " + BspCore.pieces(player));
        BspCore.testTeleportTicket(player, 500); // a fresh ticket for the rest of the test

        // An item to use up that is gone by the time the price is paid: the move is refused and the credit goes back at once.
        AccessRule pearl = AccessRule.of("ITEM", "minecraft:ender_pearl", 1, true);
        paid[0] = null;
        AccessChecks.charge(player, new AccessChecks.Result(null, 0, 300, List.of(pearl)), 0, 0, "test portal", p -> paid[0] = p);
        h.assertTrue(paid[0] == null, "refused");
        h.assertTrue(BspCore.pieces(player) == bank && BspCore.teleportPieces(player) == bank + 500, "and the credit is back on the ticket: " + BspCore.teleportPieces(player));
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void credit_with_an_empty_bank_is_not_refused(GameTestHelper h) {
        ServerPlayer player = withPieces(h, 0);
        long bank = BspCore.pieces(player);
        h.assertTrue(bank == 0, "the test player's bank is empty: " + bank);
        ticket(h, player, 500);

        AccessChecks.Result rules = AccessChecks.evaluate(player, List.of(AccessRule.of("PIECES", "", 400, false)), AccessGrant.Kind.WARP, "test");
        h.assertTrue(rules.ok(), "a PIECES rule passes on credit alone: " + rules.refusal());
        AccessChecks.Paid[] paid = {null};
        AccessChecks.charge(player, rules, 0, 0, "test warp", p -> paid[0] = p);
        h.assertTrue(paid[0] != null && paid[0].payment() != null, "paid: " + paid[0]);
        BspCore.Payment payment = paid[0].payment();
        h.assertTrue(payment.credit() == 400 && payment.bank() == 0 && payment.creditLeft() == 100 && !payment.ticketSpent(),
                "all from the ticket, 100 left on it: " + payment);
        paid[0] = null;
        AccessChecks.charge(player, new AccessChecks.Result(null, 0, 0, List.of()), 0, 101, "test warp", p -> paid[0] = p);
        h.assertTrue(paid[0] == null && BspCore.teleportPieces(player) == 100, "more than the credit left, with no bank, is refused and nothing taken");
        h.succeed();
    }
}
