package com.mrgregles.bsp_teleport.mixin;

import com.mrgregles.bsp_teleport.BSPTeleport;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * Client only, diagnostic for B20/B21 ("Can't serialize unregistered packet" on a server switch). When the client is
 * about to send a packet its connection's current state cannot carry (a play packet while the proxy's switch has put
 * the connection back into login), it logs the packet's class and the code that sent it, once per packet class, at
 * WARN in latest.log. It never changes what is sent; vanilla still refuses the packet as before.
 */
@Mixin(Connection.class)
public abstract class ConnectionDiagnosticMixin {
    private static final java.util.Set<String> bsp_teleport$reported = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Shadow
    @Final
    private PacketFlow receiving;

    @Shadow
    private Channel channel;

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At("HEAD"))
    private void bsp_teleport$checkSend(Packet<?> packet, @Nullable PacketSendListener listener, CallbackInfo ci) {
        try {
            Channel ch = channel;
            if (ch == null) {
                return;
            }
            ConnectionProtocol protocol = ch.attr(Connection.ATTRIBUTE_PROTOCOL).get();
            // HANDSHAKING is skipped: vanilla sends the next state's first packet (status ping, login hello) just before switching.
            if (protocol == null || protocol == ConnectionProtocol.HANDSHAKING || protocol.getPacketId(receiving.getOpposite(), packet) != -1) {
                return;
            }
            String name = packet.getClass().getName();
            if (bsp_teleport$reported.add(name + "@" + protocol)) {
                BSPTeleport.LOGGER.warn("BSP-Teleport diagnostic: the client is sending {} while its connection is in the {} state; "
                        + "this is the packet behind \"Can't serialize unregistered packet\". It was sent from:", name, protocol,
                        new Throwable("sender of " + packet.getClass().getSimpleName()));
            }
        } catch (RuntimeException e) {
            // a diagnostic must never break sending
        }
    }
}
