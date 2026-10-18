package com.mrgregles.bsp_teleport.block;

import com.mrgregles.bsp_teleport.portal.PortalColour;
import com.mrgregles.bsp_teleport.portal.Portals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;

/**
 * The lit opening of a ready, open portal (D58): placed and removed by {@link Portals}, never by hand. Not solid, glows,
 * hums, and moves a player who steps into it (T54). One grey texture, tinted by the colour property on the client.
 */
public class PortalFieldBlock extends Block {
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
    public static final EnumProperty<PortalColour> COLOUR = EnumProperty.create("colour", PortalColour.class);
    private static final VoxelShape X_SHAPE = Block.box(0, 0, 6, 16, 16, 10);
    private static final VoxelShape Z_SHAPE = Block.box(6, 0, 0, 10, 16, 16);

    public PortalFieldBlock() {
        super(Properties.of().mapColor(MapColor.COLOR_CYAN).strength(-1.0F, 3600000.0F).noCollission().noLootTable().noOcclusion()
                .lightLevel(s -> 11).sound(SoundType.GLASS).pushReaction(PushReaction.BLOCK));
        registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.X).setValue(COLOUR, PortalColour.DEFAULT));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS, COLOUR);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return state.getValue(AXIS) == Direction.Axis.X ? X_SHAPE : Z_SHAPE;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean willHarvest, FluidState fluid) {
        return PortalBlocks.mayBreak(player) && super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }

    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (level instanceof ServerLevel sl && entity instanceof ServerPlayer player) {
            Portals.touched(sl, pos, player);
        }
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(100) == 0) {
            level.playLocalSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SoundEvents.PORTAL_AMBIENT, SoundSource.BLOCKS, 0.3F, random.nextFloat() * 0.4F + 0.8F, false);
        }
        int tint = state.getValue(COLOUR).tint();
        Vector3f rgb = new Vector3f(((tint >> 16) & 0xFF) / 255F, ((tint >> 8) & 0xFF) / 255F, (tint & 0xFF) / 255F);
        for (int i = 0; i < 2; i++) {
            boolean x = state.getValue(AXIS) == Direction.Axis.X;
            double px = pos.getX() + (x ? random.nextDouble() : 0.5 + (random.nextDouble() - 0.5) * 0.25);
            double pz = pos.getZ() + (x ? 0.5 + (random.nextDouble() - 0.5) * 0.25 : random.nextDouble());
            level.addParticle(new DustParticleOptions(rgb, 0.8F), px, pos.getY() + random.nextDouble(), pz, 0, 0.01, 0);
        }
    }
}
