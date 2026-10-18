package com.mrgregles.bsp_teleport.access;

import net.minecraft.nbt.CompoundTag;

/**
 * What an Access Token item stands for (D74): one token per server, region, portal or warp. The rent or full tier of a
 * sold stack lives in the stack; this is the token's meaning.
 *
 * @param token     1 to 20
 * @param rentHours the rent length a rent stack of this token grants (D77)
 * @param hint      where to buy it, shown on the denied popup (D81); empty for none
 */
public record TokenAssignment(int token, AccessGrant.Kind kind, String target, int rentHours, String hint) {
    public static final int COUNT = 20;
    private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII", "XIII", "XIV", "XV", "XVI", "XVII", "XVIII", "XIX", "XX"};

    public static String roman(int token) {
        return token >= 1 && token <= COUNT ? ROMAN[token - 1] : String.valueOf(token);
    }

    public String describe() {
        return kind.name().toLowerCase(java.util.Locale.ROOT) + " " + target;
    }

    public TokenAssignment withHours(int hours) {
        return new TokenAssignment(token, kind, target, hours, hint);
    }

    public TokenAssignment withHint(String hint) {
        return new TokenAssignment(token, kind, target, rentHours, hint);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Token", token);
        tag.putString("Kind", kind.name());
        tag.putString("Target", target);
        tag.putInt("RentHours", rentHours);
        tag.putString("Hint", hint);
        return tag;
    }

    public static TokenAssignment load(CompoundTag tag) {
        return new TokenAssignment(tag.getInt("Token"), AccessGrant.Kind.valueOf(tag.getString("Kind")), tag.getString("Target"), tag.getInt("RentHours"), tag.getString("Hint"));
    }
}
