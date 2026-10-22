package com.mrgregles.bsp_teleport.network;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.admin.AdminAction;
import com.mrgregles.bsp_teleport.admin.AdminData;
import com.mrgregles.bsp_teleport.admin.AdminService;
import com.mrgregles.bsp_teleport.band.BandAction;
import com.mrgregles.bsp_teleport.band.BandData;
import com.mrgregles.bsp_teleport.band.BandService;
import com.mrgregles.bsp_teleport.access.AccessAction;
import com.mrgregles.bsp_teleport.access.AccessPopup;
import com.mrgregles.bsp_teleport.access.AccessService;
import com.mrgregles.bsp_teleport.access.AccessSync;
import com.mrgregles.bsp_teleport.portal.PortalChoice;
import com.mrgregles.bsp_teleport.portal.PortalChooser;
import com.mrgregles.bsp_teleport.portal.PortalScreenAction;
import com.mrgregles.bsp_teleport.portal.PortalScreenData;
import com.mrgregles.bsp_teleport.portal.PortalScreenService;
import net.minecraft.core.BlockPos;
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
    /** "2" since T69 and T73 changed packets 2, 11 and added 13: an older client is told to update instead of misreading them. */
    private static final String PROTOCOL = "2";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(BSPTeleport.MODID, "main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private BandPackets() {
    }

    public static void register() {
        CHANNEL.registerMessage(0, Data.class, (m, buf) -> m.data().encode(buf), buf -> new Data(BandData.decode(buf)), Data::handle);
        CHANNEL.registerMessage(1, Action.class, (m, buf) -> buf.writeEnum(m.action()).writeUtf(m.arg()), buf -> new Action(buf.readEnum(BandAction.class), buf.readUtf()), Action::handle);
        CHANNEL.registerMessage(2, Admin.class, (m, buf) -> m.data().encode(buf), buf -> new Admin(AdminData.decode(buf)), Admin::handle);
        CHANNEL.registerMessage(4, Portal.class, (m, buf) -> m.data().encode(buf), buf -> new Portal(PortalScreenData.decode(buf)), Portal::handle);
        CHANNEL.registerMessage(5, PortalDo.class, (m, buf) -> {
            buf.writeBlockPos(m.pos());
            buf.writeEnum(m.action()).writeUtf(m.arg());
        }, buf -> new PortalDo(buf.readBlockPos(), buf.readEnum(PortalScreenAction.class), buf.readUtf()), PortalDo::handle);
        CHANNEL.registerMessage(6, Access.class, (m, buf) -> m.data().encode(buf), buf -> new Access(AccessSync.decode(buf)), Access::handle);
        CHANNEL.registerMessage(7, Popup.class, (m, buf) -> m.data().encode(buf), buf -> new Popup(AccessPopup.decode(buf)), Popup::handle);
        CHANNEL.registerMessage(8, AccessDo.class, (m, buf) -> buf.writeEnum(m.action()).writeUtf(m.arg()), buf -> new AccessDo(buf.readEnum(AccessAction.class), buf.readUtf()), AccessDo::handle);
        CHANNEL.registerMessage(9, BoardOpen.class, (m, buf) -> buf.writeBlockPos(m.pos()), buf -> new BoardOpen(buf.readBlockPos()), BoardOpen::handle);
        CHANNEL.registerMessage(10, BoardConfig.class, (m, buf) -> {
            buf.writeBlockPos(m.pos());
            buf.writeVarInt(m.style());
            buf.writeVarInt(m.size());
            buf.writeUtf(m.title());
        }, buf -> new BoardConfig(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt(), buf.readUtf()), BoardConfig::handle);
        CHANNEL.registerMessage(11, Choice.class, (m, buf) -> m.choice().encode(buf), buf -> new Choice(PortalChoice.decode(buf)), Choice::handle);
        CHANNEL.registerMessage(12, Choose.class, (m, buf) -> buf.writeVarInt(m.index()), buf -> new Choose(buf.readVarInt()), Choose::handle);
        CHANNEL.registerMessage(13, Warmup.class, (m, buf) -> buf.writeUtf(m.portal()).writeVarInt(m.totalTicks()).writeVarInt(m.ticksLeft()).writeBoolean(m.held()),
                buf -> new Warmup(buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean()), Warmup::handle);
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

    public static void sendBoardOpen(ServerPlayer player, BlockPos origin) {
        if (canReceive(player)) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new BoardOpen(origin));
        }
    }

    public static void sendBoardConfig(BlockPos pos, int style, int size, String title) {
        CHANNEL.sendToServer(new BoardConfig(pos, style, size, title));
    }

    public record BoardOpen(BlockPos pos) {
        void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_teleport.client.AccessBoardScreen.open(pos)));
            ctx.get().setPacketHandled(true);
        }
    }

    public record BoardConfig(BlockPos pos, int style, int size, String title) {
        void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player != null && com.mrgregles.bsp_teleport.perm.Permissions.isAdmin(player) && player.serverLevel().isLoaded(pos)
                        && player.serverLevel().getBlockEntity(pos) instanceof com.mrgregles.bsp_teleport.block.AccessBoardBlockEntity board) {
                    board.configure(style, size, title);
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public static void sendPopup(ServerPlayer player, AccessPopup data) {
        if (canReceive(player)) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Popup(data));
        }
    }

    public static void sendAccessAction(AccessAction action, String arg) {
        CHANNEL.sendToServer(new AccessDo(action, arg));
    }

    public record Popup(AccessPopup data) {
        void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_teleport.client.AccessScreen.show(data)));
            ctx.get().setPacketHandled(true);
        }
    }

    public record AccessDo(AccessAction action, String arg) {
        void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player != null) {
                    AccessService.handle(player, action, arg);
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public static void sendAccess(ServerPlayer player, AccessSync data) {
        if (canReceive(player)) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Access(data));
        }
    }

    public record Access(AccessSync data) {
        void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_teleport.client.ClientAccess.accept(data)));
            ctx.get().setPacketHandled(true);
        }
    }

    public static void sendPortal(ServerPlayer player, PortalScreenData data) {
        if (canReceive(player)) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Portal(data));
        }
    }

    public static void sendPortalAction(BlockPos pos, PortalScreenAction action, String arg) {
        CHANNEL.sendToServer(new PortalDo(pos, action, arg));
    }

    public record Portal(PortalScreenData data) {
        void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_teleport.client.PortalScreen.show(data)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Packet 11: the destination chooser (D97); an empty option list closes it. */
    public record Choice(PortalChoice choice) {
        void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_teleport.client.PortalChooserScreen.show(choice)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Packet 12: the player's pick, or -1 for cancel. */
    public record Choose(int index) {
        void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player != null) {
                    PortalChooser.answer(player, index);
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** Packet 13: a portal warm-up's box at the top of the screen (D126); an empty portal name hides it. */
    public record Warmup(String portal, int totalTicks, int ticksLeft, boolean held) {
        void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_teleport.client.PortalWarmupHud.show(portal, totalTicks, ticksLeft, held)));
            ctx.get().setPacketHandled(true);
        }
    }

    public static void sendWarmup(ServerPlayer player, String portal, int totalTicks, int ticksLeft, boolean held) {
        if (player.connection != null && player.connection.isAcceptingMessages()) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Warmup(portal, totalTicks, ticksLeft, held));
        }
    }

    public static void sendChoice(ServerPlayer player, PortalChoice choice) {
        if (player.connection != null && player.connection.isAcceptingMessages()) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Choice(choice));
        }
    }

    public record PortalDo(BlockPos pos, PortalScreenAction action, String arg) {
        void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player != null) {
                    PortalScreenService.handle(player, pos, action, arg);
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
