package com.mrgregles.bsp_teleport.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

import java.util.Locale;

/**
 * A segment of a closed portal's hangar doors (D60): solid, placed and removed by the portal, never by hand. The part
 * says which texture it shows: a body cell, the cell at either door's meeting edge (hazard stripe toward the middle),
 * or hidden, the invisible solid cell behind the Rendered door style. Behaviour comes with T55.
 */
public class PortalDoorBlock extends Block {
    public enum Part implements StringRepresentable {
        BODY, EDGE_LEFT, EDGE_RIGHT, HIDDEN;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    public PortalDoorBlock() {
        super(Properties.of().mapColor(MapColor.SNOW).strength(-1.0F, 3600000.0F).noLootTable().noOcclusion().sound(SoundType.METAL).pushReaction(PushReaction.BLOCK));
        registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.X).setValue(PART, Part.BODY));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS, PART);
    }

    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean willHarvest, FluidState fluid) {
        return PortalBlocks.mayBreak(player) && super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }
}
