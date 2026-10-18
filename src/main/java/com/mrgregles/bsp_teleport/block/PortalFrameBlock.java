package com.mrgregles.bsp_teleport.block;

import com.mrgregles.bsp_teleport.portal.Portals;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/** A Portal Frame block: the ring around a portal's opening. Admin-only; immune to explosions, pistons and mining (D53). */
public class PortalFrameBlock extends Block {
    public PortalFrameBlock() {
        super(Properties.of().mapColor(MapColor.SNOW).strength(-1.0F, 3600000.0F).noLootTable().sound(SoundType.METAL).pushReaction(PushReaction.BLOCK));
    }

    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean willHarvest, FluidState fluid) {
        return PortalBlocks.mayBreak(player) && super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moving) {
        super.onPlace(state, level, pos, oldState, moving);
        if (!state.is(oldState.getBlock()) && level instanceof ServerLevel sl) {
            Portals.changed(sl, pos);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        super.onRemove(state, level, pos, newState, moving);
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel sl) {
            Portals.changed(sl, pos);
        }
    }
}
