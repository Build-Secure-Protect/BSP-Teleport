package com.mrgregles.bsp_teleport.registry;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.item.BandItem;
import com.mrgregles.bsp_teleport.item.PortalBlockItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BSPTeleport.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BSPTeleport.MODID);

    /** The band as an item: for the creative tab and admins. Every player has one in the band slot without it. */
    public static final RegistryObject<Item> TELEPORT_BAND = ITEMS.register("teleport_band", () -> new BandItem(new Item.Properties().stacksTo(1)));

    /** The portal blocks (D53): admin-only to place. */
    public static final RegistryObject<Item> PORTAL_CORE = ITEMS.register("portal_core", () -> new PortalBlockItem(ModBlocks.PORTAL_CORE.get()));
    public static final RegistryObject<Item> PORTAL_FRAME = ITEMS.register("portal_frame", () -> new PortalBlockItem(ModBlocks.PORTAL_FRAME.get()));

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.bsp_teleport"))
            .icon(() -> TELEPORT_BAND.get().getDefaultInstance())
            .displayItems((params, out) -> {
                out.accept(TELEPORT_BAND.get());
                out.accept(PORTAL_CORE.get());
                out.accept(PORTAL_FRAME.get());
            })
            .build());

    private ModItems() {
    }
}
