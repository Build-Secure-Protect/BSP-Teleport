package com.mrgregles.bsp_teleport.access;

import com.mrgregles.bsp_teleport.shop.ShopEntry;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Token prices (D189 to D193), the rent length an admin types (D191), the pop-up packet and the USE NOW trip (D187). */
class TokenPriceTest {

    @Test
    void textRoundTripAndOldRows() {
        TokenPrice p = new TokenPrice(true, 500, true, true, 5000, false);
        assertEquals(p, TokenPrice.decode(p.encode()));
        // D190: a row from before prices (empty column) or anything unreadable is not for sale.
        assertEquals(TokenPrice.NONE, TokenPrice.decode(""));
        assertEquals(TokenPrice.NONE, TokenPrice.decode(null));
        assertEquals(TokenPrice.NONE, TokenPrice.decode("1;abc;1;0;0;1"));
        assertFalse(TokenPrice.NONE.sellsAny());
    }

    @Test
    void valuesKeptInRange() {
        TokenPrice bad = new TokenPrice(true, -5, true, true, Integer.MAX_VALUE, true).clean();
        assertEquals(0, bad.rentPrice());
        assertEquals(TokenPrice.MAX_PRICE, bad.fullPrice());
        assertEquals(TokenPrice.decode("1;-7;1;1;2000000000;0"), new TokenPrice(true, 0, true, true, TokenPrice.MAX_PRICE, false));
    }

    @Test
    void oneTierChanges() {
        TokenPrice p = TokenPrice.NONE.with(false, true, 500, true);
        assertTrue(p.sells(false) && !p.sells(true));
        assertEquals(500, p.price(false));
        p = p.with(true, true, 5000, false);
        assertTrue(p.sells(true));
        assertFalse(p.pieces(true));
        assertEquals(500, p.price(false), "the other tier is kept");
    }

    @Test
    void lengthsTyped() {
        assertEquals(168, TokenPrice.parseLength("7d"));
        assertEquals(36, TokenPrice.parseLength("1d 12h"));
        assertEquals(36, TokenPrice.parseLength("1D12H"));
        assertEquals(36, TokenPrice.parseLength("36h"));
        assertEquals(36, TokenPrice.parseLength("36"));
        assertEquals(-1, TokenPrice.parseLength(""));
        assertEquals(-1, TokenPrice.parseLength("0"));
        assertEquals(-1, TokenPrice.parseLength("366d"));
        assertEquals(-1, TokenPrice.parseLength("a week"));
        assertEquals(-1, TokenPrice.parseLength("h"));
        assertEquals("7d", TokenPrice.describeLength(168));
        assertEquals("1d 12h", TokenPrice.describeLength(36));
        assertEquals("6h", TokenPrice.describeLength(6));
    }

    @Test
    void packetsRoundTrip() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        TokenPrice p = new TokenPrice(true, 500, true, false, 0, true);
        p.write(buf);
        new ShopEntry(3, false, true).write(buf);
        AccessPopup popup = new AccessPopup(AccessPopup.Type.DENIED, "BSP-Frostwarf", 3, 168, "Access Shop at spawn", false, List.of(), 120, 1_240L);
        popup.encode(buf);
        assertEquals(p, TokenPrice.read(buf));
        assertEquals(new ShopEntry(3, false, true), ShopEntry.read(buf));
        assertEquals(popup, AccessPopup.decode(buf));
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void tripGoesAheadOnlySoonAndNear() {
        long t = 1_000_000;
        assertTrue(AccessService.tripStillOn(t, t + 30_000, true, 2));
        assertFalse(AccessService.tripStillOn(t, t + AccessService.TRIP_MS + 1, true, 2), "too late");
        assertFalse(AccessService.tripStillOn(t, t + 30_000, true, AccessService.TRIP_REACH + 0.5), "walked away");
        assertFalse(AccessService.tripStillOn(t, t + 30_000, false, 0), "another level");
    }
}
