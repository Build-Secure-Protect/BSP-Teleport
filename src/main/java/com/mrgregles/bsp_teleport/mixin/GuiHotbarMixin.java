package com.mrgregles.bsp_teleport.mixin;

import com.mrgregles.bsp_teleport.client.BandClient;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Client only. While the band slot is selected, vanilla's hotbar highlight moves onto the band slot instead of staying
 * on the hand's real slot, so only one slot looks selected. The highlight is the second {@code blit} in
 * {@code renderHotbar} (the first is the bar itself); its x argument is index 1.
 */
@Mixin(Gui.class)
public abstract class GuiHotbarMixin {
    @ModifyArg(method = "renderHotbar(FLnet/minecraft/client/gui/GuiGraphics;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIIII)V", ordinal = 1),
            index = 1)
    private int bsp_teleport$moveSelectionToBand(int x) {
        return BandClient.isSelected() ? BandClient.slotX() - 1 : x;
    }
}
