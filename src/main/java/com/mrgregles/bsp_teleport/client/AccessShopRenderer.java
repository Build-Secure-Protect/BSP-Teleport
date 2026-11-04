package com.mrgregles.bsp_teleport.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mrgregles.bsp_teleport.block.AccessShopBlock;
import com.mrgregles.bsp_teleport.block.AccessShopBlockEntity;
import com.mrgregles.bsp_teleport.registry.ModItems;
import com.mrgregles.bsp_teleport.shop.ShopEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Draws the Access Shop's screen (D174, D178): the admin's message, wrapped to up to three lines, and under it the coins
 * of the first three tokens the shop sells. The screen is the recess in the model from x 4 to 28 and y 17 to 29 (model
 * pixels, front at z 10; see tools/gen_access_shop_assets.py), on a canvas of 8 units per model pixel.
 */
public class AccessShopRenderer implements BlockEntityRenderer<AccessShopBlockEntity> {
    /** The screen recess in model pixels; keep in step with tools/gen_access_shop_assets.py. */
    private static final float X0 = 4, X1 = 28, Y0 = 17, Y1 = 29, FRONT = 10;
    private static final int K = 8, W = (int) ((X1 - X0) * K), H = (int) ((Y1 - Y0) * K);
    private static final int TEXT = 0xFFE6EAF2, TQ = 0xFF19D3B0;

    private final Font font;

    public AccessShopRenderer(BlockEntityRendererProvider.Context ctx) {
        this.font = ctx.getFont();
    }

    @Override
    public boolean shouldRenderOffScreen(AccessShopBlockEntity shop) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 48;
    }

    @Override
    public void render(AccessShopBlockEntity shop, float partialTick, PoseStack pose, MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (shop.getLevel() == null || !(shop.getBlockState().getBlock() instanceof AccessShopBlock)) {
            return;
        }
        Direction facing = shop.getBlockState().getValue(AccessShopBlock.FACING);
        pose.pushPose();
        // Into the shop's own frame (as the blockstate turns the model), then onto the screen's top-left corner as the
        // viewer sees it: the model runs toward +x into the wing, which is on the viewer's left.
        pose.translate(0.5, 0.5, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        pose.translate(0.5 - X1 / 16f, Y1 / 16f - 0.5, 0.5 - FRONT / 16f + 0.004);
        pose.scale(1f / (16 * K), -1f / (16 * K), 1f / (16 * K));

        List<ShopEntry> entries = shop.entries();
        boolean coins = !entries.isEmpty();
        float textArea = coins ? H * 0.62f : H;
        List<FormattedCharSequence> lines = font.split(Component.literal(shop.message().isEmpty() ? shop.name() : shop.message()), W - 12);
        int count = Math.min(3, lines.size());
        float lineH = 10, top = (textArea - count * lineH) / 2 + 2;
        for (int i = 0; i < count; i++) {
            FormattedCharSequence line = lines.get(i);
            float x = (W - font.width(line)) / 2f;
            font.drawInBatch(line, x, top + i * lineH, i == 0 ? TQ : TEXT, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        }
        if (coins) {
            int n = Math.min(3, entries.size());
            float size = H * 0.26f, gap = size * 1.5f, cy = textArea + (H - textArea) / 2f, x0 = W / 2f - gap * (n - 1) / 2f;
            for (int i = 0; i < n; i++) {
                int token = entries.get(i).token();
                if (token < 1 || token > ModItems.ACCESS_TOKENS.length) {
                    continue;
                }
                pose.pushPose();
                pose.translate(x0 + i * gap, cy, 1.5f);
                pose.scale(size, -size, size * 0.02f);
                Minecraft.getInstance().getItemRenderer().renderStatic(new ItemStack(ModItems.ACCESS_TOKENS[token - 1].get()), ItemDisplayContext.GUI,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, pose, buffers, shop.getLevel(), 0);
                pose.popPose();
            }
        }
        pose.popPose();
    }
}
