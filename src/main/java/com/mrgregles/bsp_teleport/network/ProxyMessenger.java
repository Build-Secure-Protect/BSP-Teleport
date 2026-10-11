package com.mrgregles.bsp_teleport.network;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.mrgregles.bsp_teleport.BSPTeleport;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Talks to the proxy through the BungeeCord plugin-message channel, which Velocity answers while
 * {@code bungee-plugin-message-channel} is on (its default).
 */
public final class ProxyMessenger {
    private static final ResourceLocation CHANNEL = new ResourceLocation("bungeecord", "main");

    private ProxyMessenger() {
    }

    /** Asks the proxy to move the player to another server. Returns false only when nothing could be sent. */
    public static boolean connect(ServerPlayer player, String server) {
        if (player.connection == null || server.isEmpty()) {
            return false;
        }
        // The proxy protocol uses Java's DataOutput strings, not Minecraft's length-prefixed ones.
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Connect");
        out.writeUTF(server);
        player.connection.send(new ClientboundCustomPayloadPacket(CHANNEL, new FriendlyByteBuf(Unpooled.wrappedBuffer(out.toByteArray()))));
        BSPTeleport.LOGGER.debug("Asked the proxy to move {} to '{}'", player.getGameProfile().getName(), server);
        return true;
    }
}
