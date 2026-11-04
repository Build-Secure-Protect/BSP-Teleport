package com.mrgregles.bsp_teleport.block;

import com.mrgregles.bsp_teleport.shop.AccessShops;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The Access Shop (D174 to D180): two blocks wide, two tall, one deep, in BSP-Core's steel like its Trade Post, turned
 * to face the player who places it. The lower block on the right as you face it is the controller: it holds the shop's
 * settings and draws the screen; the column on your left is its clockwise neighbour ({@code wing}). Admins place it;
 * players cannot mine, push or blow it up; an admin breaking any part removes all four. A click anywhere opens the shop.
 */
public class AccessShopBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    public static final BooleanProperty WING = BooleanProperty.create("wing");

    public AccessShopBlock() {
        super(Properties.of().mapColor(MapColor.COLOR_GRAY).strength(-1.0F, 3600000.0F).noLootTable().sound(SoundType.METAL).noOcclusion()
                .pushReaction(PushReaction.BLOCK).lightLevel(s -> 7));
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(HALF, DoubleBlockHalf.LOWER).setValue(WING, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF, WING);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    public static boolean isController(BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER && !state.getValue(WING);
    }

    /** The controller's position for any part. */
    public static BlockPos controller(BlockState state, BlockPos pos) {
        BlockPos p = state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
        return state.getValue(WING) ? p.relative(state.getValue(FACING).getCounterClockWise()) : p;
    }

    /** The four parts' positions, the controller first. */
    public static List<BlockPos> parts(BlockPos controller, Direction facing) {
        BlockPos wing = controller.relative(facing.getClockWise());
        return List.of(controller, controller.above(), wing, wing.above());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return isController(state) ? new AccessShopBlockEntity(pos, state) : null;
    }

    // Four parts

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction facing = ctx.getHorizontalDirection().getOpposite();
        return roomFor(ctx.getLevel(), ctx.getClickedPos(), facing) ? defaultBlockState().setValue(FACING, facing) : null;
    }

    /** Whether a shop facing {@code facing} with its controller at {@code controller} has its other three places free. */
    public static boolean roomFor(Level level, BlockPos controller, Direction facing) {
        for (BlockPos p : parts(controller, facing)) {
            if (p.getY() >= level.getMaxBuildHeight() || !p.equals(controller) && !level.getBlockState(p).canBeReplaced()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
        placeParts(level, pos, state.getValue(FACING));
        if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof AccessShopBlockEntity shop) {
            AccessShops.remember(server, pos, shop);
        }
    }

    /** Sets the three parts beside and above a controller (placing, and GameTests). */
    public static void placeParts(Level level, BlockPos controller, Direction facing) {
        BlockState base = level.getBlockState(controller);
        List<BlockPos> p = parts(controller, facing);
        level.setBlock(p.get(1), base.setValue(HALF, DoubleBlockHalf.UPPER), 3);
        level.setBlock(p.get(2), base.setValue(WING, true), 3);
        level.setBlock(p.get(3), base.setValue(HALF, DoubleBlockHalf.UPPER).setValue(WING, true), 3);
    }

    /** A part whose vertical or side neighbour in the shop has gone goes too, so a shop is never left half standing. */
    @Override
    public BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        boolean wing = state.getValue(WING);
        Direction facing = state.getValue(FACING);
        Direction vertical = state.getValue(HALF) == DoubleBlockHalf.UPPER ? Direction.DOWN : Direction.UP;
        Direction side = wing ? facing.getCounterClockWise() : facing.getClockWise();
        if (dir == vertical || dir == side) {
            boolean ok = neighbour.is(this) && neighbour.getValue(FACING) == facing
                    && (dir == vertical ? neighbour.getValue(HALF) != state.getValue(HALF) && neighbour.getValue(WING) == wing
                                        : neighbour.getValue(WING) != wing && neighbour.getValue(HALF) == state.getValue(HALF));
            if (!ok) {
                return Blocks.AIR.defaultBlockState();
            }
        }
        return super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
    }

    /** Even in creative mode only an admin removes it; then all four parts go. */
    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean willHarvest, FluidState fluid) {
        if (!PortalBlocks.mayBreak(player)) {
            return false;
        }
        BlockPos c = controller(state, pos);
        for (BlockPos p : parts(c, state.getValue(FACING))) {
            if (!p.equals(pos) && level.getBlockState(p).getBlock() instanceof AccessShopBlock) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 35);
            }
        }
        return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }

    /** The controller going (by any means) takes the shop off the server's list (D179). */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!newState.is(this) && isController(state) && level instanceof ServerLevel server) {
            AccessShops.forget(server, pos);
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (player instanceof ServerPlayer sp) {
            AccessShops.use(sp, controller(state, pos));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
