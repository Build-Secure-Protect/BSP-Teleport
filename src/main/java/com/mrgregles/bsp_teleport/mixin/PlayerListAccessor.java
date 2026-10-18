package com.mrgregles.bsp_teleport.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Server only. Reaches the writable player list ({@code getPlayers()} returns an unmodifiable view on Forge), so the
 * GameTests can seat a mock player where system broadcasts reach it. No production code uses this.
 */
@Mixin(PlayerList.class)
public interface PlayerListAccessor {
    @Accessor("players")
    List<ServerPlayer> bsp_teleport$players();
}
