package com.mrgregles.bsp_teleport.network;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.admin.AdminAction;
import com.mrgregles.bsp_teleport.admin.AdminData;
import com.mrgregles.bsp_teleport.admin.AdminService;
import com.mrgregles.bsp_teleport.band.BandAction;
import com.mrgregles.bsp_teleport.band.BandData;
import com.mrgregles.bsp_teleport.band.BandService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/** The mod's own channel: the band screen's data down, its actions up. */
public final class BandPackets {
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(BSPTeleport.MODID, "main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private BandPackets() {
    }

    public static void register() {
        CHANNEL.registerMessage(0, Data.class, (m, buf) -> m.data().encode(buf), buf -> new Data(BandData.decode(buf)), Data::handle);
        CHANNEL.registerMessage(1, Action.class, (m, buf) -> buf.writeEnum(m.action()).writeUtf(m.arg()), buf -> new Action(buf.readEnum(BandAction.class), buf.readUtf()), Action::handle);
        CHANNEL.registerMessage(2, Admin.class, (m, buf) -> m.data().encode(buf), buf -> new Admin(AdminData.decode(buf)), Admin::handle);
        CHANNEL.registerMessage(3, AdminDo.class, (m, buf) -> buf.writeEnum(m.action()).writeUtf(m.arg()), buf -> new AdminDo(buf.readEnum(AdminAction.class), buf.readUtf()), AdminDo::handle);
    }

    public static void sendAdmin(ServerPlayer player, AdminData data) {
        if (canReceive(player)) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Admin(data));
        }
    }

    /** False while the player is leaving or not yet fully in: a play packet then would break the connection. */
    private static boolean canReceive(ServerPlayer player) {
        return player.connection != null && player.connection.isAcceptingMessages() && !player.hasDisconnected();
    }

    public static void sendAdminAction(AdminAction action, String arg) {
        CHANNEL.sendToServer(new AdminDo(action, arg));
    }

    public record Admin(AdminData data) {
        void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_teleport.client.AdminScreen.show(data)));
            ctx.get().setPacketHandled(true);
        }
    }

    public record AdminDo(AdminAction action, String arg) {
        void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player != null) {
                    AdminService.handle(player, action, arg);
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public static void sendData(ServerPlayer player, BandData data) {
        if (canReceive(player)) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Data(data));
        }
    }

    public static void sendAction(BandAction action, String arg) {
        CHANNEL.sendToServer(new Action(action, arg));
    }

    public record Data(BandData data) {
        void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_teleport.client.BandClient.show(data)));
            ctx.get().setPacketHandled(true);
        }
    }

    public record Action(BandAction action, String arg) {
        void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player != null) {
                    BandService.handle(player, action, arg);
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }
}
