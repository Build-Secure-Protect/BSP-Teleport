package com.mrgregles.bsp_teleport.gametest;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.access.AccessChecks;
import com.mrgregles.bsp_teleport.access.AccessGrant;
import com.mrgregles.bsp_teleport.access.AccessRule;
import com.mrgregles.bsp_teleport.access.OpenHours;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.data.Warp;
import com.mrgregles.bsp_teleport.perm.Permissions;
import com.mrgregles.bsp_teleport.portal.PortalDestination;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * GameTests for T82: the TIME rule (D153 to D158) on a real player and a real warp. The windows are built round the
 * server's time now, so the test does not depend on when it runs.
 */
@GameTestHolder(BSPTeleport.MODID)
@PrefixGameTestTemplate(false)
public class OpenHoursGameTests {
    private static final String EMPTY = "empty";
    private static final String WARP = "tp_test_hours";

    /** A window of {@code minutes} starting {@code from} minutes after now, as "HH:MM-HH:MM". */
    private static String window(int from, int minutes) {
        int open = Math.floorMod(OpenHours.now() + from, OpenHours.DAY);
        return OpenHours.hhmm(open) + "-" + OpenHours.hhmm((open + minutes) % OpenHours.DAY);
    }

    @GameTest(template = EMPTY)
    public static void a_time_rule_refuses_outside_its_hours_and_says_when_it_opens(GameTestHelper h) {
        ServerPlayer player = TestPlayers.newPlayer(h);
        if (Permissions.isAdmin(player)) {
            h.fail("the mock player should not be an admin");
            return;
        }
        // A minute either side of now, so a tick across a minute boundary cannot flip the answer.
        AccessRule open = AccessRule.of("TIME", window(-2, 5), 0, false);
        AccessRule shut = AccessRule.of("TIME", window(10, 30), 0, false);
        h.assertTrue(open != null && shut != null, "the windows read");
        h.assertTrue(AccessChecks.evaluate(player, List.of(open), AccessGrant.Kind.WARP, WARP).ok(), "open now: the rule passes");
        AccessChecks.Result refused = AccessChecks.evaluate(player, List.of(shut), AccessGrant.Kind.WARP, WARP);
        h.assertFalse(refused.ok(), "closed now: the rule refuses");
        String said = refused.refusal().getString();
        String opens = OpenHours.hhmm(Math.floorMod(OpenHours.now() + 10, OpenHours.DAY));
        h.assertTrue(said.contains(opens) && said.contains(OpenHours.zone().getId()), "the refusal names the opening time and zone: " + said);
        h.assertTrue(AccessChecks.opensAt(List.of(shut)) >= 0 && AccessChecks.opensAt(List.of(open)) == -2 && AccessChecks.opensAt(List.of()) == -1, "the band's open state");

        // D158: a second TIME rule joins the first, so the list holds one rule open in both windows.
        List<AccessRule> joined = AccessRule.add(List.of(shut), open);
        h.assertTrue(joined != null && joined.size() == 1 && joined.get(0).hours().windows().size() == 2, "the windows joined into one rule");
        h.assertTrue(AccessChecks.evaluate(player, joined, AccessGrant.Kind.WARP, WARP).ok(), "and it passes in either window");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void a_portal_entry_to_a_closed_warp_is_shut(GameTestHelper h) {
        ServerPlayer player = TestPlayers.newPlayer(h);
        TeleportData data = TeleportData.get(h.getLevel().getServer());
        Position at = new Position(TeleportConfig.serverName(), "minecraft:overworld", 0, 80, 0, 0, 0);
        try {
            data.setWarp(new Warp(WARP, at, 0, false, "", false, AccessRule.serializeList(List.of(AccessRule.of("TIME", window(10, 30), 0, false)))));
            Component closed = com.mrgregles.bsp_teleport.portal.Portals.warpClosedForTest(player, PortalDestination.warp(WARP, false));
            h.assertTrue(closed != null && closed.getString().contains(OpenHours.zone().getId()), "the entry is shut with the opening line");
            data.setWarp(new Warp(WARP, at, 0, false, "", false, AccessRule.serializeList(List.of(AccessRule.of("TIME", window(-2, 5), 0, false)))));
            h.assertTrue(com.mrgregles.bsp_teleport.portal.Portals.warpClosedForTest(player, PortalDestination.warp(WARP, false)) == null, "open now: the entry is usable");
        } finally {
            data.deleteWarp(WARP);
        }
        h.succeed();
    }
}
