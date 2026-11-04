package com.mrgregles.bsp_teleport.shop;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * What the Access Shop screen shows (D174, D179): the shop's settings, the player's coins and bank pieces, and for an
 * admin the other shops on this server to copy from. What each token opens and what the player holds come from the
 * client's access sync, which every player already has.
 */
public record ShopView(BlockPos pos, String name, String message, List<ShopEntry> entries, boolean admin, int coins, long pieces, List<Source> sources) {
    public record Source(String key, String label) {
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(name).writeUtf(message);
        buf.writeVarInt(entries.size());
        entries.forEach(e -> e.write(buf));
        buf.writeBoolean(admin);
        buf.writeVarInt(coins);
        buf.writeVarLong(pieces);
        buf.writeVarInt(sources.size());
        sources.forEach(s -> buf.writeUtf(s.key()).writeUtf(s.label()));
    }

    public static ShopView decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String name = buf.readUtf(), message = buf.readUtf();
        int n = buf.readVarInt();
        List<ShopEntry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            entries.add(ShopEntry.read(buf));
        }
        boolean admin = buf.readBoolean();
        int coins = buf.readVarInt();
        long pieces = buf.readVarLong();
        n = buf.readVarInt();
        List<Source> sources = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            sources.add(new Source(buf.readUtf(), buf.readUtf()));
        }
        return new ShopView(pos, name, message, entries, admin, coins, pieces, sources);
    }
}
