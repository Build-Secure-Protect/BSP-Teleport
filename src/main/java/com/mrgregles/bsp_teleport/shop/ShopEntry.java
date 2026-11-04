package com.mrgregles.bsp_teleport.shop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;

/**
 * One token an Access Shop sells (D174, D177): rent for {@code rentHours} at {@code rentPrice} and/or full access at
 * {@code fullPrice}, each in bank pieces or Shatter Coins. A price of 0 gives that tier away.
 */
public record ShopEntry(int token, boolean rent, int rentHours, int rentPrice, boolean rentPieces, boolean full, int fullPrice, boolean fullPieces) {
    public static final int MAX_HOURS = 24 * 365, MAX_PRICE = 1_000_000_000;

    /** A new slot for this token: rent for a day, nothing priced yet, in pieces. */
    public static ShopEntry fresh(int token) {
        return new ShopEntry(token, true, 24, 0, true, false, 0, true);
    }

    /** Values kept in range, so a bad packet or file cannot store anything odd. */
    public ShopEntry clean() {
        return new ShopEntry(Mth.clamp(token, 1, com.mrgregles.bsp_teleport.access.TokenAssignment.COUNT), rent, Mth.clamp(rentHours, 1, MAX_HOURS),
                Mth.clamp(rentPrice, 0, MAX_PRICE), rentPieces, full, Mth.clamp(fullPrice, 0, MAX_PRICE), fullPieces);
    }

    /** Whether this tier is on sale here. */
    public boolean sells(boolean fullTier) {
        return fullTier ? full : rent;
    }

    public int price(boolean fullTier) {
        return fullTier ? fullPrice : rentPrice;
    }

    public boolean pieces(boolean fullTier) {
        return fullTier ? fullPieces : rentPieces;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Token", token);
        tag.putBoolean("Rent", rent);
        tag.putInt("RentHours", rentHours);
        tag.putInt("RentPrice", rentPrice);
        tag.putBoolean("RentPieces", rentPieces);
        tag.putBoolean("Full", full);
        tag.putInt("FullPrice", fullPrice);
        tag.putBoolean("FullPieces", fullPieces);
        return tag;
    }

    public static ShopEntry load(CompoundTag tag) {
        return new ShopEntry(tag.getInt("Token"), tag.getBoolean("Rent"), tag.getInt("RentHours"), tag.getInt("RentPrice"), tag.getBoolean("RentPieces"),
                tag.getBoolean("Full"), tag.getInt("FullPrice"), tag.getBoolean("FullPieces")).clean();
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(token);
        buf.writeBoolean(rent);
        buf.writeVarInt(rentHours);
        buf.writeVarInt(rentPrice);
        buf.writeBoolean(rentPieces);
        buf.writeBoolean(full);
        buf.writeVarInt(fullPrice);
        buf.writeBoolean(fullPieces);
    }

    public static ShopEntry read(FriendlyByteBuf buf) {
        return new ShopEntry(buf.readVarInt(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readVarInt(),
                buf.readBoolean()).clean();
    }
}
