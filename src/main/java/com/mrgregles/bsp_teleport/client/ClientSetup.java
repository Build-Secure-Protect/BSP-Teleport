package com.mrgregles.bsp_teleport.client;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.block.PortalFieldBlock;
import com.mrgregles.bsp_teleport.registry.ModBlocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Mod-bus client hooks; only loaded on the client. */
@Mod.EventBusSubscriber(modid = BSPTeleport.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientSetup {
    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onKeys(RegisterKeyMappingsEvent event) {
        BandClient.onRegisterKeys(event);
    }

    @SubscribeEvent
    public static void onSetup(FMLClientSetupEvent event) {
        BandClient.onClientSetup(event);
        HomeServersBoard.register();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(AccessHud::onOverlay);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(PortalWarmupHud::onOverlay);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut e) -> {
            ClientAccess.clear();
            PortalWarmupHud.clear();
        });
    }

    /** The Rendered door style (D60) is drawn by a block entity renderer on the core. */
    @SubscribeEvent
    public static void onRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlocks.PORTAL_CORE_ENTITY.get(), PortalCoreRenderer::new);
        event.registerBlockEntityRenderer(ModBlocks.ACCESS_BOARD_ENTITY.get(), AccessBoardRenderer::new);
    }

    /** The portal field's grey texture takes its colour from the block state (D58). */
    @SubscribeEvent
    public static void onBlockColours(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tintIndex) -> state.getValue(PortalFieldBlock.COLOUR).tint(), ModBlocks.PORTAL_FIELD.get());
    }
}
