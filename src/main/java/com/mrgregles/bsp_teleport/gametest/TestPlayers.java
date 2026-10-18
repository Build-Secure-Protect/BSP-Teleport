package com.mrgregles.bsp_teleport.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.util.UUID;

/**
 * Server players for GameTests, the way BSP-Core and Region Guard make them: vanilla's mock server player gets no
 * channel and Forge's packet listener touches the channel in its constructor, so the player is built on an in-memory
 * netty channel instead. Packets sent to the player go nowhere unless a test wraps the connection. The player is not
 * in the server's player list.
 */
final class TestPlayers {
    private static int counter;

    private TestPlayers() {
    }

    /** A fresh connection on an in-memory channel. */
    static Connection connection() {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection); // registers and activates the handler, which gives the connection its channel
        return connection;
    }

    /** A new player with a random UUID and a name of letters, digits and underscores only. */
    static ServerPlayer mockServerPlayer(GameTestHelper h) {
        Connection connection = connection();
        ServerPlayer player = newPlayer(h);
        player.connection = new ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, player);
        return player;
    }

    /** A new player without a packet listener; the caller attaches one. */
    static ServerPlayer newPlayer(GameTestHelper h) {
        String name = "tp_test_" + (++counter);
        return new ServerPlayer(h.getLevel().getServer(), h.getLevel(), new GameProfile(UUID.randomUUID(), name));
    }
}
