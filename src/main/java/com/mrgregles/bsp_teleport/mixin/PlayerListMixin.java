package com.mrgregles.bsp_teleport.mixin;

import com.mrgregles.bsp_teleport.network.JoinLeaveMessages;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server only. Marks the login window ({@code placeNewPlayer}, where vanilla and Bukkit broadcast the join line) and
 * drops that line at the head of {@code broadcastSystemMessage} when {@code network.suppressJoinLeaveMessages} is on.
 * Only HEAD and RETURN injections, so the method bodies, which Mohist rewrites, are never touched.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(method = "placeNewPlayer", at = @At("HEAD"))
    private void bsp_teleport$loginStart(Connection connection, ServerPlayer player, CallbackInfo ci) {
        JoinLeaveMessages.begin(player);
    }

    @Inject(method = "placeNewPlayer", at = @At("RETURN"))
    private void bsp_teleport$loginEnd(Connection connection, ServerPlayer player, CallbackInfo ci) {
        JoinLeaveMessages.end();
    }

    @Inject(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"), cancellable = true)
    private void bsp_teleport$dropJoinLeave(Component message, boolean overlay, CallbackInfo ci) {
        if (JoinLeaveMessages.shouldDrop(message)) {
            ci.cancel();
        }
    }
}
