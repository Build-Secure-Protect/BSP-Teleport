package com.mrgregles.bsp_teleport.client;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.admin.AdminAction;
import com.mrgregles.bsp_teleport.band.BandAction;
import com.mrgregles.bsp_teleport.network.BandPackets;
import com.mrgregles.bsp_teleport.access.AccessAction;
import com.mrgregles.bsp_teleport.portal.PortalScreenAction;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;

/**
 * The client's one way to send to the server, which drops the packet while the connection cannot take it.
 *
 * <p>While the proxy moves the player to another server the client's connection is briefly back in the login state
 * and the player object may still exist. A play packet sent then ("Can't serialize unregistered packet") kicks the
 * player, so nothing is sent unless the connection is up and the game listener is the one in charge.
 */
public final class ClientNet {
    private ClientNet() {
    }

    public static boolean ready() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener listener = mc.getConnection();
        if (listener == null || mc.level == null) {
            return false;
        }
        Connection connection = listener.getConnection();
        return connection != null && connection.isConnected() && connection.getPacketListener() == listener;
    }

    public static void send(BandAction action, String arg) {
        if (ready()) {
            BandPackets.sendAction(action, arg);
        } else {
            BSPTeleport.LOGGER.debug("Dropped band action {} while the connection was changing", action);
        }
    }

    public static void sendBoard(BlockPos pos, int style, int size, String title) {
        if (ready()) {
            BandPackets.sendBoardConfig(pos, style, size, title);
        }
    }

    public static void sendAccess(AccessAction action, String arg) {
        if (ready()) {
            BandPackets.sendAccessAction(action, arg);
        } else {
            BSPTeleport.LOGGER.debug("Dropped access action {} while the connection was changing", action);
        }
    }

    public static void sendPortal(BlockPos pos, PortalScreenAction action, String arg) {
        if (ready()) {
            BandPackets.sendPortalAction(pos, action, arg);
        } else {
            BSPTeleport.LOGGER.debug("Dropped portal action {} while the connection was changing", action);
        }
    }

    /** The chooser's answer (packet 12): an option index, or -1 to cancel. */
    public static void sendPortalChoice(int index) {
        if (ready()) {
            BandPackets.CHANNEL.sendToServer(new BandPackets.Choose(index));
        }
    }

    public static void sendAdmin(AdminAction action, String arg) {
        if (ready()) {
            BandPackets.sendAdminAction(action, arg);
        } else {
            BSPTeleport.LOGGER.debug("Dropped admin action {} while the connection was changing", action);
        }
    }

    /** D185: BUY in the access pop-up, which can open around a server switch (the arrival bounce). */
    public static void sendOfferBuy(int token, int price, boolean full, boolean take) {
        if (ready()) {
            BandPackets.sendOfferBuy(token, price, full, take);
        } else {
            BSPTeleport.LOGGER.debug("Dropped a pop-up purchase while the connection was changing");
        }
    }

    public static void sendShopBuy(BlockPos pos, int index, int token, int price, boolean full, boolean take) {
        if (ready()) {
            BandPackets.sendShopBuy(pos, index, token, price, full, take);
        }
    }

    public static void sendShopSave(BlockPos pos, String name, String message, String copyFrom, java.util.List<com.mrgregles.bsp_teleport.shop.ShopEntry> entries) {
        if (ready()) {
            BandPackets.sendShopSave(pos, name, message, copyFrom, entries);
        }
    }
}
