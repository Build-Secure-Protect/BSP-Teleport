package com.mrgregles.bsp_teleport.mixin;

import com.mrgregles.bsp_teleport.network.JoinLeaveMessages;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server only. Marks the logout window: {@code onDisconnect} is where vanilla broadcasts the leave line (before
 * removing the player) and where Bukkit broadcasts the quit message (after removing them). It is a
 * {@code PacketListener} interface method, so it exists with this signature on Forge and on Mohist.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void bsp_teleport$logoutStart(Component reason, CallbackInfo ci) {
        JoinLeaveMessages.begin(this.player);
    }

    @Inject(method = "onDisconnect", at = @At("RETURN"))
    private void bsp_teleport$logoutEnd(Component reason, CallbackInfo ci) {
        JoinLeaveMessages.end();
    }
}
