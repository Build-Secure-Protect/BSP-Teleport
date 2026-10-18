package com.mrgregles.bsp_teleport;

import com.mrgregles.bsp_teleport.access.AccessGrant;
import com.mrgregles.bsp_teleport.access.AccessGrant.Kind;
import com.mrgregles.bsp_teleport.access.AccessGrant.Tier;
import com.mrgregles.bsp_teleport.access.AccessRule;
import com.mrgregles.bsp_teleport.access.AccessRules;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D68 to D78 on lists of grants. */
class AccessRulesTest {
    private static final UUID P = UUID.randomUUID();
    private static final long NOW = 1_000_000_000L;
    private static final Set<String> RESTRICTED = Set.of("BSP-Forest", "BSP-Wilds", "BSP-Caves");

    private static AccessGrant g(Kind kind, String target, Tier tier, long expires) {
        return new AccessGrant(P, kind, target, tier, expires, NOW);
    }

    @Test
    void untaggedServersAreOpenAndRestrictedOnesNeedAGrant() {
        assertTrue(AccessRules.mayEnter(List.of(), RESTRICTED, "BSP-Spawn", NOW));
        assertTrue(AccessRules.mayEnter(List.of(), RESTRICTED, "", NOW));
        assertFalse(AccessRules.mayEnter(List.of(), RESTRICTED, "BSP-Wilds", NOW));
        assertTrue(AccessRules.mayEnter(List.of(g(Kind.SERVER, "BSP-Wilds", Tier.MAIN, 0)), RESTRICTED, "bsp-wilds", NOW));
        assertTrue(AccessRules.mayEnter(List.of(g(Kind.SERVER, "BSP-Wilds", Tier.FULL, 0)), RESTRICTED, "BSP-Wilds", NOW));
        assertTrue(AccessRules.mayEnter(List.of(g(Kind.SERVER, "BSP-Wilds", Tier.RENT, NOW + 1)), RESTRICTED, "BSP-Wilds", NOW));
        assertFalse(AccessRules.mayEnter(List.of(g(Kind.SERVER, "BSP-Wilds", Tier.RENT, NOW)), RESTRICTED, "BSP-Wilds", NOW));
    }

    @Test
    void mainIsChosenOnceAndFullCannotBeTopped() {
        List<AccessGrant> none = List.of();
        assertNull(AccessRules.refuse(none, Kind.SERVER, "BSP-Forest", Tier.MAIN, NOW));
        List<AccessGrant> withMain = List.of(g(Kind.SERVER, "BSP-Forest", Tier.MAIN, 0));
        assertEquals("access.main_already", AccessRules.refuse(withMain, Kind.SERVER, "BSP-Wilds", Tier.MAIN, NOW));
        assertEquals("access.already_full", AccessRules.refuse(withMain, Kind.SERVER, "BSP-Forest", Tier.RENT, NOW));
        List<AccessGrant> withFull = List.of(g(Kind.SERVER, "BSP-Wilds", Tier.FULL, 0));
        assertEquals("access.already_full", AccessRules.refuse(withFull, Kind.SERVER, "BSP-Wilds", Tier.RENT, NOW));
        assertEquals("access.already_full", AccessRules.refuse(withFull, Kind.SERVER, "BSP-Wilds", Tier.FULL, NOW));
        assertNull(AccessRules.refuse(withFull, Kind.SERVER, "BSP-Caves", Tier.RENT, NOW));
    }

    @Test
    void rentAddsUpAndFullReplacesRent() {
        long day = AccessRules.rentMs(24);
        List<AccessGrant> rented = AccessRules.apply(List.of(), g(Kind.SERVER, "BSP-Caves", Tier.RENT, NOW + day), NOW);
        assertEquals(1, rented.size());
        assertEquals(NOW + day, rented.get(0).expiresMs());
        List<AccessGrant> more = AccessRules.apply(rented, g(Kind.SERVER, "BSP-Caves", Tier.RENT, NOW + day), NOW);
        assertEquals(1, more.size());
        assertEquals(NOW + 2 * day, more.get(0).expiresMs());
        // An expired rent does not add: the new one starts fresh.
        List<AccessGrant> fresh = AccessRules.apply(List.of(g(Kind.SERVER, "BSP-Caves", Tier.RENT, NOW - 1)), g(Kind.SERVER, "BSP-Caves", Tier.RENT, NOW + day), NOW);
        assertEquals(NOW + day, fresh.get(0).expiresMs());
        List<AccessGrant> full = AccessRules.apply(more, g(Kind.SERVER, "BSP-Caves", Tier.FULL, 0), NOW);
        assertEquals(1, full.size());
        assertEquals(Tier.FULL, full.get(0).tier());
        assertTrue(full.get(0).active(NOW + 100 * day));
    }

    @Test
    void countdownTextAndWarningStages() {
        assertEquals("6d 5h", AccessGrant.describeLeft((6 * 24 + 5) * 3_600_000L + 30_000));
        assertEquals("23h 59m", AccessGrant.describeLeft(23 * 3_600_000L + 59 * 60_000L));
        assertEquals("9m", AccessGrant.describeLeft(9 * 60_000L));
        assertEquals("1m", AccessGrant.describeLeft(1));
        assertEquals(0, AccessRules.warningStage(2 * 3_600_000L));
        assertEquals(1, AccessRules.warningStage(3_600_000L));
        assertEquals(2, AccessRules.warningStage(600_000L));
        assertEquals(3, AccessRules.warningStage(59_000L));
    }

    @Test
    void ruleListsRoundTripAndBadInputIsRefused() {
        List<AccessRule> rules = List.of(AccessRule.of("rank", "Warden", 0, false), AccessRule.of("item", "minecraft:ender_pearl", 4, true),
                AccessRule.of("coins", "", 50, false), AccessRule.of("token", "", 0, false), AccessRule.of("node", "bsp.vip", 0, false));
        String text = AccessRule.serializeList(rules);
        assertEquals(rules, AccessRule.parseList(text));
        assertEquals("item minecraft:ender_pearl x4 (used up)", rules.get(1).describe());
        assertNull(AccessRule.of("rank", "", 0, false));
        assertNull(AccessRule.of("coins", "", 0, false));
        assertNull(AccessRule.of("nonsense", "x", 1, false));
        assertTrue(AccessRule.parseList("").isEmpty());
        assertEquals(1, AccessRule.parseList("GARBAGE|x|1|false;COINS||5|false").size());
    }
}
