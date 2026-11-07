package com.mrgregles.bsp_teleport.gametest;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.access.AccessGrant;
import com.mrgregles.bsp_teleport.access.AccessRules;
import com.mrgregles.bsp_teleport.access.AccessService;
import com.mrgregles.bsp_teleport.access.TokenAssignment;
import com.mrgregles.bsp_teleport.access.TokenPrice;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.shop.AccessShops;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * D185 to D193 on the server: a token keeps its price through a save, the access pop-up sells only at the token's own
 * price, and USE NOW carries on with the refused trip (D187). The pop-up screen itself needs a client and is the owner's
 * in-game check.
 */
@GameTestHolder(BSPTeleport.MODID)
@PrefixGameTestTemplate(false)
public class AccessOfferGameTests {
    private static final String TARGET = "TP-Offer-Test";

    @GameTest(template = "empty")
    public static void a_token_keeps_its_price_through_a_save(GameTestHelper h) {
        TokenPrice price = new TokenPrice(true, 500, true, true, 5000, false);
        TokenAssignment token = new TokenAssignment(20, AccessGrant.Kind.SERVER, TARGET, 168, "Access Shop at spawn", price);
        CompoundTag tag = token.save();
        h.assertTrue(TokenAssignment.load(tag).equals(token), "the price survives a save and load: " + TokenAssignment.load(tag));
        tag.remove("Prices");
        h.assertTrue(TokenAssignment.load(tag).price().equals(TokenPrice.NONE), "a token saved before prices is not for sale (D190)");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void use_now_in_the_pop_up_buys_at_the_token_price_and_carries_on(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        TeleportData data = TeleportData.get(server);
        boolean enabledBefore = TeleportConfig.ACCESS_ENABLED.get();
        boolean restrictedBefore = data.restrictedServers().stream().anyMatch(s -> s.equalsIgnoreCase(TARGET));
        TokenAssignment tokenBefore = AccessService.tokens(server).get(20);
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        player.moveTo(h.absoluteVec(new net.minecraft.world.phys.Vec3(1.5, 2, 1.5)));
        AtomicInteger trips = new AtomicInteger();
        Runnable cleanup = () -> {
            AccessService.revoke(server, player.getUUID(), AccessGrant.Kind.SERVER, TARGET);
            if (tokenBefore == null) {
                AccessService.clearToken(server, 20);
            } else {
                AccessService.setToken(server, tokenBefore);
            }
            data.setRestricted(TARGET, restrictedBefore);
            TeleportConfig.ACCESS_ENABLED.set(enabledBefore);
        };
        try {
            TeleportConfig.ACCESS_ENABLED.set(true);
            data.setRestricted(TARGET, true);
            // Rent on sale for free (so no bank is needed here); full access not for sale.
            AccessService.setToken(server, new TokenAssignment(20, AccessGrant.Kind.SERVER, TARGET, 48, "", TokenPrice.NONE.with(false, true, 0, true)));
            h.assertFalse(AccessService.mayEnter(player, TARGET), "no access to start with");

            AccessService.deny(player, AccessGrant.Kind.SERVER, TARGET, trips::incrementAndGet);
            AccessShops.buyOffered(player, 20, 0, true, false);
            AccessShops.buyOffered(player, 20, 7, false, false);
            h.assertFalse(AccessService.mayEnter(player, TARGET), "a tier not for sale, or a price other than the token's, is refused");
        } catch (RuntimeException | AssertionError e) {
            cleanup.run();
            throw e;
        }
        AccessShops.buyOffered(player, 20, 0, false, false);
        h.succeedWhen(() -> {
            h.assertTrue(AccessRules.has(AccessService.grantsOf(server, player.getUUID()), AccessGrant.Kind.SERVER, TARGET, System.currentTimeMillis()),
                    "USE NOW grants the rent");
            h.assertTrue(trips.get() == 1, "and the refused trip goes ahead once: " + trips.get());
            AccessService.resumeTrip(player, AccessGrant.Kind.SERVER, TARGET);
            h.assertTrue(trips.get() == 1, "a trip is carried on only once");
            long expiry = AccessRules.find(AccessService.grantsOf(server, player.getUUID()), AccessGrant.Kind.SERVER, TARGET).expiresMs();
            AccessShops.buyOffered(player, 20, 0, false, false);
            h.assertTrue(AccessRules.find(AccessService.grantsOf(server, player.getUUID()), AccessGrant.Kind.SERVER, TARGET).expiresMs() == expiry,
                    "the pop-up sells nothing to a player who already has access");
            cleanup.run();
        });
    }
}
