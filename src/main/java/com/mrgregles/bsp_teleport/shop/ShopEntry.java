package com.mrgregles.bsp_teleport.shop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;

/**
 * One token an Access Shop sells (D174, D177, D189): which token and whether this shop sells its rent and/or its full
 * tier. The price and the rent length are the token's own ({@code TokenAssignment.price()}, set on the TOKENS tab), so a
 * tier is on sale here only when both the shop and the token's price have it on (D190).
 *
 * <p>Shops saved before D189 also stored prices and hours per slot; those keys are ignored now.
 */
public record ShopEntry(int token, boolean rent, boolean full) {

    /** A new slot for this token: both tiers, priced by the token. */
    public static ShopEntry fresh(int token) {
        return new ShopEntry(token, true, true);
    }

    /** Values kept in range, so a bad packet or file cannot store anything odd. */
    public ShopEntry clean() {
        return new ShopEntry(Mth.clamp(token, 1, com.mrgregles.bsp_teleport.access.TokenAssignment.COUNT), rent, full);
    }

    /** Whether this shop sells that tier (the token's price decides whether it is for sale at all). */
    public boolean sells(boolean fullTier) {
        return fullTier ? full : rent;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Token", token);
        tag.putBoolean("Rent", rent);
        tag.putBoolean("Full", full);
        return tag;
    }

    public static ShopEntry load(CompoundTag tag) {
        return new ShopEntry(tag.getInt("Token"), tag.getBoolean("Rent"), tag.getBoolean("Full")).clean();
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(token);
        buf.writeBoolean(rent);
        buf.writeBoolean(full);
    }

    public static ShopEntry read(FriendlyByteBuf buf) {
        return new ShopEntry(buf.readVarInt(), buf.readBoolean(), buf.readBoolean()).clean();
    }
}
