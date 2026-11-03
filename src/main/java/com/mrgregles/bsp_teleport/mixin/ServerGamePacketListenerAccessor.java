package com.mrgregles.bsp_teleport.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Server only. B38: how many movement packets the client has sent on this connection. Vanilla counts them up for its
 * flood check and never resets the count; the client sends its first one only once it has accepted the login position
 * and the chunk under it has loaded, so a count above 0 means the client is in the world.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerAccessor {
    @Accessor("receivedMovePacketCount")
    int bsp_teleport$receivedMovePacketCount();
}
