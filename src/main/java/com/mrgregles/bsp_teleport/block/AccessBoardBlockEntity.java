package com.mrgregles.bsp_teleport.block;

import com.mrgregles.bsp_teleport.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;

/** One Access Board panel. Every panel can hold settings, but only the board's bottom-left panel's are used: it draws the whole board. */
public class AccessBoardBlockEntity extends BlockEntity {
    public static final int STYLE_BARS = 0, STYLE_TILES = 1, STYLE_COLUMNS = 2, TITLE_MAX = 28;

    private int style = STYLE_BARS, size = 100;
    private String title = "HOME SERVERS";

    public AccessBoardBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.ACCESS_BOARD_ENTITY.get(), pos, state);
    }

    public int style() {
        return style;
    }

    public int size() {
        return size;
    }

    public String title() {
        return title;
    }

    public void configure(int style, int size, String title) {
        this.style = Mth.clamp(style, 0, 2);
        this.size = Mth.clamp(size, 60, 160);
        this.title = title.length() > TITLE_MAX ? title.substring(0, TITLE_MAX) : title;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /** The joined board can reach well past this block, so it must not be culled with it. */
    @Override
    public AABB getRenderBoundingBox() {
        return new AABB(worldPosition).inflate(AccessBoardBlock.MAX_W);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("Style", style);
        tag.putInt("Size", size);
        tag.putString("Title", title);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("Title")) {
            style = tag.getInt("Style");
            size = Mth.clamp(tag.getInt("Size"), 60, 160);
            title = tag.getString("Title");
        }
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        return tag;
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
