package com.mrgregles.bsp_teleport.admin;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/** Everything the admin panel shows, built on the server and sent whole. */
public record AdminData(String server, boolean readOnly, String storage, String messaging,
                        List<Setting> settings, List<WarpRow> warps, List<ServerRow> servers, List<PortalEntry> portals,
                        List<TokenRow> tokens, List<String> restricted, boolean accessOn, boolean singlePlayer) {

    /** One config value. {@code kind} is INT, BOOL or TEXT; INT values carry min, max and step. */
    public record Setting(String id, String value, String kind, int min, int max, int step) {
    }

    public record WarpRow(String name, String server, int cost, boolean restricted, String icon, boolean portal, String rules) {
    }

    /** One Access Token's assignment (D74); kind and target empty when free. */
    public record TokenRow(int token, String kind, String target, int hours, String hint) {
    }

    /** One portal on the network, from its shared row. */
    public record PortalEntry(String id, String name, String server, String world, int x, int y, int z, String destination, String linkId, String status, String doors, List<String> leadsTo) {
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
            buf.writeUtf(w.name()).writeUtf(w.server()).writeUtf(w.icon());
            buf.writeVarInt(w.cost());
            buf.writeBoolean(w.restricted());
            buf.writeBoolean(w.portal());
            buf.writeUtf(w.rules());
        }
        buf.writeVarInt(servers.size());
        for (ServerRow s : servers) {
            buf.writeUtf(s.name());
            buf.writeBoolean(s.up());
            buf.writeBoolean(s.here());
            buf.writeVarInt(s.players().size());
            s.players().forEach(buf::writeUtf);
        }
        buf.writeVarInt(portals.size());
        for (PortalEntry p : portals) {
            buf.writeUtf(p.id()).writeUtf(p.name()).writeUtf(p.server()).writeUtf(p.world());
            buf.writeInt(p.x());
            buf.writeInt(p.y());
            buf.writeInt(p.z());
            buf.writeUtf(p.destination()).writeUtf(p.linkId()).writeUtf(p.status()).writeUtf(p.doors());
            buf.writeVarInt(p.leadsTo().size());
            p.leadsTo().forEach(buf::writeUtf);
        }
        buf.writeVarInt(tokens.size());
        for (TokenRow t : tokens) {
            buf.writeVarInt(t.token());
            buf.writeUtf(t.kind()).writeUtf(t.target());
            buf.writeVarInt(t.hours());
            buf.writeUtf(t.hint());
        }
        buf.writeVarInt(restricted.size());
        restricted.forEach(buf::writeUtf);
        buf.writeBoolean(accessOn);
        buf.writeBoolean(singlePlayer);
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
            String wn = buf.readUtf(), ws = buf.readUtf(), wi = buf.readUtf();
            int cost = buf.readVarInt();
            boolean restricted = buf.readBoolean(), portal = buf.readBoolean();
            warps.add(new WarpRow(wn, ws, cost, restricted, wi, portal, buf.readUtf()));
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
        n = buf.readVarInt();
        List<PortalEntry> portals = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String id = buf.readUtf(), pn = buf.readUtf(), ps = buf.readUtf(), pw = buf.readUtf();
            int x = buf.readInt(), y = buf.readInt(), z = buf.readInt();
            String pd = buf.readUtf(), pl = buf.readUtf(), pst = buf.readUtf(), pdo = buf.readUtf();
            int m = buf.readVarInt();
            List<String> leads = new ArrayList<>(m);
            for (int k = 0; k < m; k++) {
                leads.add(buf.readUtf());
            }
            portals.add(new PortalEntry(id, pn, ps, pw, x, y, z, pd, pl, pst, pdo, leads));
        }
        n = buf.readVarInt();
        List<TokenRow> tokens = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int token = buf.readVarInt();
            String kind = buf.readUtf(), target = buf.readUtf();
            tokens.add(new TokenRow(token, kind, target, buf.readVarInt(), buf.readUtf()));
        }
        n = buf.readVarInt();
        List<String> restricted = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            restricted.add(buf.readUtf());
        }
        boolean accessOn = buf.readBoolean();
        boolean singlePlayer = buf.readBoolean();
        return new AdminData(server, readOnly, storage, messaging, settings, warps, servers, portals, tokens, restricted, accessOn, singlePlayer);
    }
}
