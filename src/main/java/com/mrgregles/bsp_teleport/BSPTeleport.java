package com.mrgregles.bsp_teleport;

import com.mojang.logging.LogUtils;
import com.mrgregles.bsp_teleport.spike.SpikeCommands;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
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
        MinecraftForge.EVENT_BUS.addListener(SpikeCommands::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(SpikeCommands::onPermissionNodes);
    }
}
