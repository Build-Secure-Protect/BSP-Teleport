package com.mrgregles.bsp_teleport;

import com.mojang.logging.LogUtils;
import com.mrgregles.bsp_teleport.access.AccessService;
import com.mrgregles.bsp_teleport.api.BspTeleportApiSetter;
import com.mrgregles.bsp_teleport.command.AdminCommands;
import com.mrgregles.bsp_teleport.command.TeleportCommands;
import com.mrgregles.bsp_teleport.compat.BspCore;
import com.mrgregles.bsp_teleport.compat.RegionGuard;
import com.mrgregles.bsp_teleport.request.TeleportRequests;
import com.mrgregles.bsp_teleport.network.BandPackets;
import com.mrgregles.bsp_teleport.network.Messenger;
import com.mrgregles.bsp_teleport.portal.Portals;
import com.mrgregles.bsp_teleport.registry.ModBlocks;
import com.mrgregles.bsp_teleport.registry.ModItems;
import com.mrgregles.bsp_teleport.storage.Storage;
import com.mrgregles.bsp_teleport.teleport.PendingLandings;
import com.mrgregles.bsp_teleport.teleport.TeleportService;
import com.mrgregles.bsp_teleport.teleport.Teleports;
import com.mrgregles.bsp_teleport.util.Scheduler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Entry point for BSP Teleport: cross-server teleports, homes and warps for the Build Secure Protect modpack.
 *
 * <p>Features are added in their own packages and wired up here.
 */
@Mod(BSPTeleport.MODID)
public class BSPTeleport {
    /** Mod id. Must match {@code mod_id} in gradle.properties and {@code modId} in mods.toml. */
    public static final String MODID = "bsp_teleport";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BSPTeleport(FMLJavaModLoadingContext context) {
        context.registerConfig(ModConfig.Type.SERVER, TeleportConfig.SPEC);
        context.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        BspTeleportApiSetter.set(TeleportService.INSTANCE);
        ModBlocks.BLOCKS.register(context.getModEventBus());
        ModBlocks.BLOCK_ENTITIES.register(context.getModEventBus());
        ModItems.ITEMS.register(context.getModEventBus());
        ModItems.TABS.register(context.getModEventBus());
        BandPackets.register();

        // Storage and messaging first: their join handlers fill the caches the others read.
        MinecraftForge.EVENT_BUS.addListener(Storage::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(Storage::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGH, Storage::onPlayerLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(Storage::onPlayerLoggedOut);
        MinecraftForge.EVENT_BUS.addListener(Storage::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(Messenger::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(Messenger::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(Messenger::onPlayerLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(Messenger::onPlayerLoggedOut);
        MinecraftForge.EVENT_BUS.addListener(Messenger::onServerTick);

        MinecraftForge.EVENT_BUS.addListener(Scheduler::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(PendingLandings::onPlayerLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(PendingLandings::onPlayerLoggedOut);
        MinecraftForge.EVENT_BUS.addListener(PendingLandings::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(Teleports::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, Teleports::onHurt);
        MinecraftForge.EVENT_BUS.addListener(Teleports::onDeath);
        MinecraftForge.EVENT_BUS.addListener(Teleports::onLoggedOut);
        MinecraftForge.EVENT_BUS.addListener(TeleportRequests::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(Portals::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(Portals::onServerTick);
        // Access runs after storage (HIGH) and the landing (normal) have had their say on login.
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOW, AccessService::onPlayerLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(AccessService::onPlayerLoggedOut);
        MinecraftForge.EVENT_BUS.addListener(AccessService::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(AccessService::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(AccessService::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(TeleportCommands::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(AdminCommands::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(AdminCommands::onPermissionNodes);
        BspCore.init();
        RegionGuard.init();
    }
}
