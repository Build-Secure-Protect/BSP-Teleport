package com.mrgregles.bsp_teleport.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/** Everything the core screen shows, built on the server and sent whole (D63). */
public record PortalScreenData(BlockPos pos, boolean readOnly, String server, String world, String name, String status, int maxSize, int maxOpening,
                               int width, int height, int cells, String destKind, String destTarget, String destText, String partner, boolean arriveOnly,
                               String colour, String doorStyle, String facing, String arrivalLine, String mode, boolean openWhenPowered, boolean powered,
                               String hours, int nowMinute, int gameOpenAt, int gameCloseAt, String zone, boolean locked, boolean closeWhenDown, int cooldown,
                               String doors, boolean lit, List<WarpEntry> warps, List<String> servers, String rules, int accessToken,
                               List<DestEntry> dests, List<PortalEntry> portals, int maxDests, int warmup) {

    public record WarpEntry(String name, String server, boolean portal) {
    }

    /** One entry of the destination list (D94): kind and target, the admin's label, the target's own name, and the entry's status name. */
    public record DestEntry(String kind, String target, String label, String own, String status) {
        public String shown() {
            return label.isEmpty() ? own : label;
        }
    }

    /** Another portal on the network, for the Portal picker (D95). */
    public record PortalEntry(String id, String name, String server) {
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeBoolean(readOnly);
        buf.writeUtf(server).writeUtf(world).writeUtf(name).writeUtf(status);
        buf.writeVarInt(maxSize);
        buf.writeVarInt(maxOpening);
        buf.writeVarInt(width);
        buf.writeVarInt(height);
        buf.writeVarInt(cells);
        buf.writeUtf(destKind).writeUtf(destTarget).writeUtf(destText).writeUtf(partner);
        buf.writeBoolean(arriveOnly);
        buf.writeUtf(colour).writeUtf(doorStyle).writeUtf(facing).writeUtf(arrivalLine).writeUtf(mode);
        buf.writeBoolean(openWhenPowered);
        buf.writeBoolean(powered);
        buf.writeUtf(hours);
        buf.writeVarInt(nowMinute);
        buf.writeVarInt(gameOpenAt);
        buf.writeVarInt(gameCloseAt);
        buf.writeUtf(zone);
        buf.writeBoolean(locked);
        buf.writeBoolean(closeWhenDown);
        buf.writeVarInt(cooldown);
        buf.writeUtf(doors);
        buf.writeBoolean(lit);
        buf.writeVarInt(warps.size());
        for (WarpEntry w : warps) {
            buf.writeUtf(w.name()).writeUtf(w.server());
            buf.writeBoolean(w.portal());
        }
        buf.writeVarInt(servers.size());
        servers.forEach(buf::writeUtf);
        buf.writeUtf(rules);
        buf.writeVarInt(accessToken);
        buf.writeVarInt(dests.size());
        for (DestEntry d : dests) {
            buf.writeUtf(d.kind()).writeUtf(d.target()).writeUtf(d.label()).writeUtf(d.own()).writeUtf(d.status());
        }
        buf.writeVarInt(portals.size());
        for (PortalEntry e : portals) {
            buf.writeUtf(e.id()).writeUtf(e.name()).writeUtf(e.server());
        }
        buf.writeVarInt(maxDests);
        buf.writeVarInt(warmup);
    }

    public static PortalScreenData decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        boolean readOnly = buf.readBoolean();
        String server = buf.readUtf(), world = buf.readUtf(), name = buf.readUtf(), status = buf.readUtf();
        int maxSize = buf.readVarInt(), maxOpening = buf.readVarInt(), width = buf.readVarInt(), height = buf.readVarInt(), cells = buf.readVarInt();
        String destKind = buf.readUtf(), destTarget = buf.readUtf(), destText = buf.readUtf(), partner = buf.readUtf();
        boolean arriveOnly = buf.readBoolean();
        String colour = buf.readUtf(), doorStyle = buf.readUtf(), facing = buf.readUtf(), arrivalLine = buf.readUtf(), mode = buf.readUtf();
        boolean openWhenPowered = buf.readBoolean(), powered = buf.readBoolean();
        String hours = buf.readUtf();
        int nowMinute = buf.readVarInt(), gameOpenAt = buf.readVarInt(), gameCloseAt = buf.readVarInt();
        String zone = buf.readUtf();
        boolean locked = buf.readBoolean(), closeWhenDown = buf.readBoolean();
        int cooldown = buf.readVarInt();
        String doors = buf.readUtf();
        boolean lit = buf.readBoolean();
        int n = buf.readVarInt();
        List<WarpEntry> warps = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String wn = buf.readUtf(), ws = buf.readUtf();
            warps.add(new WarpEntry(wn, ws, buf.readBoolean()));
        }
        n = buf.readVarInt();
        List<String> servers = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            servers.add(buf.readUtf());
        }
        return new PortalScreenData(pos, readOnly, server, world, name, status, maxSize, maxOpening, width, height, cells, destKind, destTarget, destText, partner,
                arriveOnly, colour, doorStyle, facing, arrivalLine, mode, openWhenPowered, powered, hours, nowMinute, gameOpenAt, gameCloseAt, zone, locked,
                closeWhenDown, cooldown, doors, lit, warps, servers, buf.readUtf(), buf.readVarInt(), readDests(buf), readPortals(buf), buf.readVarInt(), buf.readVarInt());
    }

    private static List<DestEntry> readDests(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<DestEntry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new DestEntry(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf()));
        }
        return out;
    }

    private static List<PortalEntry> readPortals(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<PortalEntry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new PortalEntry(buf.readUtf(), buf.readUtf(), buf.readUtf()));
        }
        return out;
    }
}
