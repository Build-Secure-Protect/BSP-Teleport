package com.mrgregles.bsp_teleport.registry;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.block.PortalCoreBlock;
import com.mrgregles.bsp_teleport.block.PortalCoreBlockEntity;
import com.mrgregles.bsp_teleport.block.PortalDoorBlock;
import com.mrgregles.bsp_teleport.block.PortalFieldBlock;
import com.mrgregles.bsp_teleport.block.PortalFrameBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** The portal blocks (D53). Items for the core and frame are in {@link ModItems}. */
public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, BSPTeleport.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, BSPTeleport.MODID);

    public static final RegistryObject<Block> PORTAL_FRAME = BLOCKS.register("portal_frame", PortalFrameBlock::new);
    public static final RegistryObject<Block> PORTAL_CORE = BLOCKS.register("portal_core", PortalCoreBlock::new);
    public static final RegistryObject<Block> PORTAL_FIELD = BLOCKS.register("portal_field", PortalFieldBlock::new);
    public static final RegistryObject<Block> PORTAL_DOOR = BLOCKS.register("portal_door", PortalDoorBlock::new);

    public static final RegistryObject<BlockEntityType<PortalCoreBlockEntity>> PORTAL_CORE_ENTITY = BLOCK_ENTITIES.register("portal_core",
            () -> BlockEntityType.Builder.of(PortalCoreBlockEntity::new, PORTAL_CORE.get()).build(null));

    private ModBlocks() {
    }
}
