package com.mrgregles.bsp_teleport.access;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * What a client knows about access (D79, D80): the player's own grants with the time left, every token assignment (for
 * tooltips and the HUD's symbols), the restricted servers, and whether the system is on. Sent whole on login and on every change.
 */
public record AccessSync(boolean enabled, List<Grant> grants, List<TokenAssignment> tokens, List<String> restricted, List<Count> counts) {

    /** A restricted server's counts for the board and the choose popup (D85). */
    public record Count(String server, int home, int full, int rent) {
    }

    /** A grant as the client sees it: time left rather than a wall-clock expiry, so clocks need not agree. */
    public record Grant(AccessGrant.Kind kind, String target, AccessGrant.Tier tier, long remainingMs) {
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(enabled);
        buf.writeVarInt(grants.size());
        for (Grant g : grants) {
            buf.writeEnum(g.kind()).writeUtf(g.target()).writeEnum(g.tier());
            buf.writeLong(g.remainingMs());
        }
        buf.writeVarInt(tokens.size());
        for (TokenAssignment t : tokens) {
            buf.writeVarInt(t.token());
            buf.writeEnum(t.kind()).writeUtf(t.target());
            buf.writeVarInt(t.rentHours());
            buf.writeUtf(t.hint());
        }
        buf.writeVarInt(restricted.size());
        restricted.forEach(buf::writeUtf);
        buf.writeVarInt(counts.size());
        for (Count c : counts) {
            buf.writeUtf(c.server());
            buf.writeVarInt(c.home());
            buf.writeVarInt(c.full());
            buf.writeVarInt(c.rent());
        }
    }

    public static AccessSync decode(FriendlyByteBuf buf) {
        boolean enabled = buf.readBoolean();
        int n = buf.readVarInt();
        List<Grant> grants = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            AccessGrant.Kind kind = buf.readEnum(AccessGrant.Kind.class);
            String target = buf.readUtf();
            AccessGrant.Tier tier = buf.readEnum(AccessGrant.Tier.class);
            grants.add(new Grant(kind, target, tier, buf.readLong()));
        }
        n = buf.readVarInt();
        List<TokenAssignment> tokens = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int token = buf.readVarInt();
            AccessGrant.Kind kind = buf.readEnum(AccessGrant.Kind.class);
            String target = buf.readUtf();
            tokens.add(new TokenAssignment(token, kind, target, buf.readVarInt(), buf.readUtf()));
        }
        n = buf.readVarInt();
        List<String> restricted = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            restricted.add(buf.readUtf());
        }
        n = buf.readVarInt();
        List<Count> counts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            counts.add(new Count(buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        }
        return new AccessSync(enabled, grants, tokens, restricted, counts);
    }
}
