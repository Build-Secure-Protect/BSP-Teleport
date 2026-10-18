package com.mrgregles.bsp_teleport.registry;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.item.AccessTokenItem;
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
    public static final RegistryObject<Item> ACCESS_BOARD = ITEMS.register("access_board", () -> new PortalBlockItem(ModBlocks.ACCESS_BOARD.get()));

    /** Access Token I to XX (D73): index 0 is token 1. */
    @SuppressWarnings("unchecked")
    public static final RegistryObject<Item>[] ACCESS_TOKENS = new RegistryObject[com.mrgregles.bsp_teleport.access.TokenAssignment.COUNT];

    static {
        for (int i = 0; i < ACCESS_TOKENS.length; i++) {
            int index = i + 1;
            ACCESS_TOKENS[i] = ITEMS.register("access_token_" + index, () -> new AccessTokenItem(index));
        }
    }

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.bsp_teleport"))
            .icon(() -> TELEPORT_BAND.get().getDefaultInstance())
            .displayItems((params, out) -> {
                out.accept(TELEPORT_BAND.get());
                out.accept(PORTAL_CORE.get());
                out.accept(PORTAL_FRAME.get());
                out.accept(ACCESS_BOARD.get());
                for (RegistryObject<Item> token : ACCESS_TOKENS) {
                    out.accept(token.get());
                }
            })
            .build());

    private ModItems() {
    }
}
