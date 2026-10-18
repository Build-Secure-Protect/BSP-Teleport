package com.mrgregles.bsp_teleport.block;

import com.mrgregles.bsp_teleport.network.BandPackets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * A panel of the Access Board (D85): a thin display block. Panels side by side and above each other, facing the same
 * way, join into one screen of up to {@link #MAX_W} by {@link #MAX_H} that shows how many players call each restricted
 * server home. The same joining rule as BSP-Core's Score Screen. Admin-only; an admin right-clicks any panel for its settings.
 */
public class AccessBoardBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final int MAX_W = 8, MAX_H = 6;
    private static final VoxelShape NORTH = Block.box(0, 0, 13, 16, 16, 16), SOUTH = Block.box(0, 0, 0, 16, 16, 3),
            WEST = Block.box(13, 0, 0, 16, 16, 16), EAST = Block.box(0, 0, 0, 3, 16, 16);

    public AccessBoardBlock() {
        super(Properties.of().mapColor(MapColor.COLOR_BLACK).strength(-1.0F, 3600000.0F).noLootTable().sound(SoundType.METAL).noOcclusion().lightLevel(s -> 7).pushReaction(PushReaction.BLOCK));
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case EAST -> EAST;
            default -> NORTH;
        };
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AccessBoardBlockEntity(pos, state);
    }

    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean willHarvest, FluidState fluid) {
        return PortalBlocks.mayBreak(player) && super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }

    // Joining, as the Score Screen does it

    private static boolean panel(BlockGetter level, BlockPos pos, Direction facing) {
        BlockState s = level.getBlockState(pos);
        return s.getBlock() instanceof AccessBoardBlock && s.getValue(FACING) == facing;
    }

    /** Viewer's right when looking at the front of a board facing {@code facing}. */
    public static Direction right(Direction facing) {
        return facing.getCounterClockWise();
    }

    /** The bottom-left panel (seen from the front) of the board {@code pos} belongs to: it holds the settings and draws the board. */
    public static BlockPos origin(BlockGetter level, BlockPos pos, Direction facing) {
        Direction left = right(facing).getOpposite();
        BlockPos p = pos;
        for (int i = 1; i < MAX_W && panel(level, p.relative(left), facing); i++) {
            p = p.relative(left);
        }
        for (int i = 1; i < MAX_H && panel(level, p.below(), facing); i++) {
            p = p.below();
        }
        return p;
    }

    /** Width and height, in panels, of the full rectangle that starts at {@code origin}. */
    public static int[] size(BlockGetter level, BlockPos origin, Direction facing) {
        Direction right = right(facing);
        int w = 1;
        while (w < MAX_W && panel(level, origin.relative(right, w), facing)) {
            w++;
        }
        int h = 1;
        rows:
        while (h < MAX_H) {
            for (int x = 0; x < w; x++) {
                if (!panel(level, origin.relative(right, x).above(h), facing)) {
                    break rows;
                }
            }
            h++;
        }
        return new int[]{w, h};
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (player instanceof ServerPlayer sp && PortalBlocks.isAdmin(sp)) {
            BandPackets.sendBoardOpen(sp, origin(level, pos, state.getValue(FACING)));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
