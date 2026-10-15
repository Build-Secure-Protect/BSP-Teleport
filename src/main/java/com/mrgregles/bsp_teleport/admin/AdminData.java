package com.mrgregles.bsp_teleport.admin;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/** Everything the admin panel shows, built on the server and sent whole. */
public record AdminData(String server, boolean readOnly, String storage, String messaging,
                        List<Setting> settings, List<WarpRow> warps, List<ServerRow> servers) {

    /** One config value. {@code kind} is INT, BOOL or TEXT; INT values carry min, max and step. */
    public record Setting(String id, String value, String kind, int min, int max, int step) {
    }

    public record WarpRow(String name, String server, int cost, boolean restricted) {
    }

    public record ServerRow(String name, boolean up, boolean here, List<String> players) {
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(server);
        buf.writeBoolean(readOnly);
        buf.writeUtf(storage);
        buf.writeUtf(messaging);
        buf.writeVarInt(settings.size());
        for (Setting s : settings) {
            buf.writeUtf(s.id()).writeUtf(s.value()).writeUtf(s.kind());
            buf.writeInt(s.min());
            buf.writeInt(s.max());
            buf.writeInt(s.step());
        }
        buf.writeVarInt(warps.size());
        for (WarpRow w : warps) {
            buf.writeUtf(w.name()).writeUtf(w.server());
            buf.writeVarInt(w.cost());
            buf.writeBoolean(w.restricted());
        }
        buf.writeVarInt(servers.size());
        for (ServerRow s : servers) {
            buf.writeUtf(s.name());
            buf.writeBoolean(s.up());
            buf.writeBoolean(s.here());
            buf.writeVarInt(s.players().size());
            s.players().forEach(buf::writeUtf);
        }
    }

    public static AdminData decode(FriendlyByteBuf buf) {
        String server = buf.readUtf();
        boolean readOnly = buf.readBoolean();
        String storage = buf.readUtf(), messaging = buf.readUtf();
        int n = buf.readVarInt();
        List<Setting> settings = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            settings.add(new Setting(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readInt(), buf.readInt(), buf.readInt()));
        }
        n = buf.readVarInt();
        List<WarpRow> warps = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            warps.add(new WarpRow(buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readBoolean()));
        }
        n = buf.readVarInt();
        List<ServerRow> servers = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String name = buf.readUtf();
            boolean up = buf.readBoolean(), here = buf.readBoolean();
            int m = buf.readVarInt();
            List<String> players = new ArrayList<>(m);
            for (int j = 0; j < m; j++) {
                players.add(buf.readUtf());
            }
            servers.add(new ServerRow(name, up, here, players));
        }
        return new AdminData(server, readOnly, storage, messaging, settings, warps, servers);
    }
}
