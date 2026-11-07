package com.mrgregles.bsp_teleport.access;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;

/**
 * What a token costs (D189 to D192): rent and/or full access, each on or off, each priced in bank pieces or Shatter
 * Coins. Set on the TOKENS tab and used by every Access Shop and the access pop-up. A tier that is off is not for sale
 * ("No price set", D190); a tier that is on at 0 is given away.
 */
public record TokenPrice(boolean rent, int rentPrice, boolean rentPieces, boolean full, int fullPrice, boolean fullPieces) {
    public static final int MAX_PRICE = 1_000_000_000;
    /** Nothing for sale: what every token starts with, and what a token had before prices moved here. */
    public static final TokenPrice NONE = new TokenPrice(false, 0, true, false, 0, true);

    public boolean sells(boolean fullTier) {
        return fullTier ? full : rent;
    }

    public boolean sellsAny() {
        return rent || full;
    }

    public int price(boolean fullTier) {
        return fullTier ? fullPrice : rentPrice;
    }

    public boolean pieces(boolean fullTier) {
        return fullTier ? fullPieces : rentPieces;
    }

    /** This price with one tier changed. */
    public TokenPrice with(boolean fullTier, boolean on, int price, boolean pieces) {
        return fullTier ? new TokenPrice(rent, rentPrice, rentPieces, on, price, pieces) : new TokenPrice(on, price, pieces, full, fullPrice, fullPieces);
    }

    /** Values kept in range, so a bad packet, command or row cannot store anything odd. */
    public TokenPrice clean() {
        return new TokenPrice(rent, Mth.clamp(rentPrice, 0, MAX_PRICE), rentPieces, full, Mth.clamp(fullPrice, 0, MAX_PRICE), fullPieces);
    }

    /** The text form kept in the shared table's {@code prices} column and sent in admin actions: six fields split by ';'. */
    public String encode() {
        return (rent ? 1 : 0) + ";" + rentPrice + ";" + (rentPieces ? 1 : 0) + ";" + (full ? 1 : 0) + ";" + fullPrice + ";" + (fullPieces ? 1 : 0);
    }

    /** {@link #encode}'s text read back; anything unreadable (an empty column from before prices) is {@link #NONE}. */
    public static TokenPrice decode(String text) {
        if (text == null) {
            return NONE;
        }
        String[] p = text.trim().split(";");
        if (p.length != 6) {
            return NONE;
        }
        try {
            return new TokenPrice("1".equals(p[0]), Integer.parseInt(p[1]), "1".equals(p[2]), "1".equals(p[3]), Integer.parseInt(p[4]), "1".equals(p[5])).clean();
        } catch (NumberFormatException e) {
            return NONE;
        }
    }

    public void save(CompoundTag tag) {
        tag.putString("Prices", encode());
    }

    public static TokenPrice load(CompoundTag tag) {
        return decode(tag.getString("Prices"));
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(rent);
        buf.writeVarInt(rentPrice);
        buf.writeBoolean(rentPieces);
        buf.writeBoolean(full);
        buf.writeVarInt(fullPrice);
        buf.writeBoolean(fullPieces);
    }

    public static TokenPrice read(FriendlyByteBuf buf) {
        return new TokenPrice(buf.readBoolean(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readVarInt(), buf.readBoolean()).clean();
    }

    /**
     * D191: a rent length typed by an admin, in hours. Accepts "7d", "7d 4h", "36h", "36" (hours) and "1d12h"; returns
     * -1 for anything else or a length outside 1 hour to a year.
     */
    public static int parseLength(String text) {
        String s = text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        if (s.isEmpty()) {
            return -1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(?:(\\d{1,4})d)?(?:(\\d{1,5})h?)?$").matcher(s);
        if (!m.matches() || (m.group(1) == null && m.group(2) == null)) {
            return -1;
        }
        long hours = (m.group(1) == null ? 0L : Long.parseLong(m.group(1)) * 24) + (m.group(2) == null ? 0L : Long.parseLong(m.group(2)));
        return hours >= 1 && hours <= 24 * 365 ? (int) hours : -1;
    }

    /** D191: hours as days and hours, the way the TOKENS tab and the pop-up show a rent length ("7d", "1d 12h", "6h"). */
    public static String describeLength(int hours) {
        int d = hours / 24, h = hours % 24;
        return d == 0 ? h + "h" : h == 0 ? d + "d" : d + "d " + h + "h";
    }
}
