package com.mrgregles.bsp_teleport.client;

import com.mrgregles.bsp_teleport.BSPTeleport;
import net.minecraftforge.api.distmarker.Dist;
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
    }
}
