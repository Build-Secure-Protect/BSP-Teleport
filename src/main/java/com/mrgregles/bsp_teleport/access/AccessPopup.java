package com.mrgregles.bsp_teleport.access;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * What a popup shows (D81, D82), built on the server and sent whole: DENIED names the thing and the token that sells it;
 * CHOOSE lists the restricted servers with their counts for the one free main-server choice.
 *
 * @param token  the token number that stands for the target, or 0
 * @param noMain whether the player still has no main server (a DENIED popup then offers the choice)
 */
public record AccessPopup(Type type, String target, int token, int rentHours, String hint, boolean noMain, List<ServerChoice> servers) {
    public enum Type { DENIED, CHOOSE }

    /** A restricted server and how many players call it home, hold full access, or rent it. */
    public record ServerChoice(String server, int token, int home, int full, int rent) {
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(type).writeUtf(target);
        buf.writeVarInt(token);
        buf.writeVarInt(rentHours);
        buf.writeUtf(hint);
        buf.writeBoolean(noMain);
        buf.writeVarInt(servers.size());
        for (ServerChoice s : servers) {
            buf.writeUtf(s.server());
            buf.writeVarInt(s.token());
            buf.writeVarInt(s.home());
            buf.writeVarInt(s.full());
            buf.writeVarInt(s.rent());
        }
    }

    public static AccessPopup decode(FriendlyByteBuf buf) {
        Type type = buf.readEnum(Type.class);
        String target = buf.readUtf();
        int token = buf.readVarInt(), hours = buf.readVarInt();
        String hint = buf.readUtf();
        boolean noMain = buf.readBoolean();
        int n = buf.readVarInt();
        List<ServerChoice> servers = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            servers.add(new ServerChoice(buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        }
        return new AccessPopup(type, target, token, hours, hint, noMain, servers);
    }
}
