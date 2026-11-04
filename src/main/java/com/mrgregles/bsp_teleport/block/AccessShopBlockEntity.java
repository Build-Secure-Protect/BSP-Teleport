package com.mrgregles.bsp_teleport.block;

import com.mrgregles.bsp_teleport.registry.ModBlocks;
import com.mrgregles.bsp_teleport.shop.AccessShops;
import com.mrgregles.bsp_teleport.shop.ShopEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * An Access Shop's settings (D180), on its controller: its name, the message on its screen and the tokens it sells in
 * order (each tab shows its own tokens in this order). Sent to clients so the screen can draw the message.
 */
public class AccessShopBlockEntity extends BlockEntity {
    public static final int NAME_MAX = 24, MESSAGE_MAX = 80, ENTRIES_MAX = 36;

    private String name = "Access Shop";
    private String message = "ACCESS TOKENS. Right-click to buy.";
    private List<ShopEntry> entries = new ArrayList<>();

    public AccessShopBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.ACCESS_SHOP_ENTITY.get(), pos, state);
    }

    public String name() {
        return name;
    }

    public String message() {
        return message;
    }

    public List<ShopEntry> entries() {
        return List.copyOf(entries);
    }

    /** New settings from the admin panel or a copy; kept in range, sent to every client near it. */
    public void configure(String name, String message, List<ShopEntry> entries) {
        String n = name.strip();
        this.name = n.isEmpty() ? "Access Shop" : n.length() > NAME_MAX ? n.substring(0, NAME_MAX) : n;
        String m = message.strip();
        this.message = m.length() > MESSAGE_MAX ? m.substring(0, MESSAGE_MAX) : m;
        List<ShopEntry> list = new ArrayList<>();
        for (ShopEntry e : entries) {
            if (list.size() < ENTRIES_MAX) {
                list.add(e.clean());
            }
        }
        this.entries = list;
        setChanged();
        if (level instanceof ServerLevel server) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            AccessShops.remember(server, worldPosition, this);
        }
    }

    /** The screen sits in the upper half and reaches into the wing, so it must not be culled with this block. */
    @Override
    public AABB getRenderBoundingBox() {
        return new AABB(worldPosition).inflate(2);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        writeSettings(tag);
    }

    /** The settings alone, as {@link AccessShops} keeps them for Copy from. */
    public void writeSettings(CompoundTag tag) {
        tag.putString("Name", name);
        tag.putString("Message", message);
        ListTag list = new ListTag();
        entries.forEach(e -> list.add(e.save()));
        tag.put("Entries", list);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("Name")) {
            name = tag.getString("Name");
            message = tag.getString("Message");
            entries = readEntries(tag);
        }
    }

    public static List<ShopEntry> readEntries(CompoundTag tag) {
        List<ShopEntry> out = new ArrayList<>();
        for (Tag t : tag.getList("Entries", Tag.TAG_COMPOUND)) {
            if (out.size() < ENTRIES_MAX) {
                out.add(ShopEntry.load((CompoundTag) t));
            }
        }
        return out;
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
