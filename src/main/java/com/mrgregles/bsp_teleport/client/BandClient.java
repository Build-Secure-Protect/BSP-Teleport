package com.mrgregles.bsp_teleport.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.band.BandAction;
import com.mrgregles.bsp_teleport.band.BandData;
import com.mrgregles.bsp_teleport.registry.ModItems;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

/**
 * The band slot: a tenth hotbar slot drawn after the hotbar that every player always has. Scrolling past the last
 * slot (or before the first) selects it; right-clicking while it is selected, or the band key (default "."),
 * opens the screen. The real selected slot stays what it was, so the hand keeps its item.
 */
public final class BandClient {
    public static final KeyMapping OPEN_KEY = new KeyMapping("key.bsp_teleport.band", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_PERIOD, "key.categories.bsp_teleport");
    static final int TQ = 0xFF19D3B0, BG = 0xF010151C, SLOT = 0xFF0C0E12, DIM = 0xFF2A2F3A, MUTED = 0xFF9AA3B5, TEXT = 0xFFE6EAF2, VIO = 0xFFB58CFF, GOLD = 0xFFFFD23A, BAD = 0xFFFF6B5C, OK = 0xFF8FE04A;

    private static boolean selected;
    private static int rememberedSlot = -1;
    private static ItemStack icon = ItemStack.EMPTY;

    private BandClient() {
    }

    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_KEY);
    }

    public static void onClientSetup(FMLClientSetupEvent event) {
        MinecraftForge.EVENT_BUS.addListener(BandClient::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(BandClient::onScroll);
        MinecraftForge.EVENT_BUS.addListener(BandClient::onInteract);
        MinecraftForge.EVENT_BUS.addListener(BandClient::onOverlay);
    }

    public static boolean isSelected() {
        return selected;
    }

    /** Left edge of the band slot's 22 px box: right of the hotbar, past the offhand slot when that sits on the right. */
    public static int slotX() {
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        boolean offhandRight = mc.player != null && mc.player.getMainArm() == net.minecraft.world.entity.HumanoidArm.LEFT;
        return w / 2 + 91 + 4 + (offhandRight && mc.player != null && !mc.player.getOffhandItem().isEmpty() ? 29 : 0);
    }

    /** The server sent fresh data: open the screen, or refresh it if it is open. */
    public static void show(BandData data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof BandScreen screen) {
            screen.update(data);
        } else {
            mc.setScreen(new BandScreen(data));
        }
    }

    public static void open() {
        ClientNet.send(BandAction.OPEN, "");
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        while (OPEN_KEY.consumeClick()) {
            if (mc.player != null) {
                open();
            }
        }
        if (mc.player == null) {
            selected = false;
            return;
        }
        // A number key or anything else that moved the hand deselects the band.
        if (selected && mc.player.getInventory().selected != rememberedSlot) {
            selected = false;
        }
    }

    private static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || mc.player.isSpectator()) {
            return;
        }
        int delta = (int) Math.signum(event.getScrollDelta());
        if (delta == 0) {
            return;
        }
        var inv = mc.player.getInventory();
        if (selected) {
            // Leaving the band: scrolling up goes to the last slot, down to the first.
            selected = false;
            inv.selected = delta > 0 ? 8 : 0;
            event.setCanceled(true);
        } else if ((delta > 0 && inv.selected == 0) || (delta < 0 && inv.selected == 8)) {
            selected = true;
            rememberedSlot = inv.selected;
            event.setCanceled(true);
        }
    }

    private static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
        if (!selected || Minecraft.getInstance().screen != null) {
            return;
        }
        if (event.isUseItem()) {
            event.setCanceled(true);
            event.setSwingHand(false);
            open();
        } else if (event.isAttack()) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    private static void onOverlay(RenderGuiOverlayEvent.Post event) {
        if (!event.getOverlay().id().equals(VanillaGuiOverlay.HOTBAR.id())) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.player.isSpectator()) {
            return;
        }
        if (icon.isEmpty()) {
            icon = new ItemStack(ModItems.TELEPORT_BAND.get());
        }
        GuiGraphics g = event.getGuiGraphics();
        int h = event.getWindow().getGuiScaledHeight();
        int x = slotX();
        int y = h - 22;
        g.fill(x - 1, y - 1, x + 23, y + 23, selected ? TQ : DIM);
        g.fill(x, y, x + 22, y + 22, selected ? 0xFF0F2A28 : SLOT);
        g.renderItem(icon, x + 3, y + 3);
        if (selected) {
            g.pose().pushPose();
            g.pose().scale(0.5f, 0.5f, 1f);
            g.drawString(mc.font, "RIGHT CLICK", (int) ((x - 2) / 0.5f), (int) ((y - 7) / 0.5f), TQ, false);
            g.pose().popPose();
        }
    }

    static void log(String s) {
        BSPTeleport.LOGGER.debug(s);
    }
}
