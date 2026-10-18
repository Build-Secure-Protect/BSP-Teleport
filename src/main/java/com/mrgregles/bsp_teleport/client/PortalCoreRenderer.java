package com.mrgregles.bsp_teleport.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.block.PortalCoreBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * The Rendered door style (D60): two hangar panels per run of the opening slide in from the ends and meet in the
 * middle, drawn with the door textures from the block atlas. The server still places invisible solid door cells
 * behind them, so the geometry here is only what the player sees. Panels are half a block thick, in the plane.
 */
public class PortalCoreRenderer implements BlockEntityRenderer<PortalCoreBlockEntity> {
    private static final ResourceLocation BODY = new ResourceLocation(BSPTeleport.MODID, "block/portal_door");
    private static final ResourceLocation EDGE = new ResourceLocation(BSPTeleport.MODID, "block/portal_door_edge");
    private static final double THICK = 0.25;

    public PortalCoreRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public boolean shouldRenderOffScreen(PortalCoreBlockEntity core) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 128;
    }

    @Override
    public void render(PortalCoreBlockEntity core, float partialTick, PoseStack pose, MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (core.doorStyle() != PortalCoreBlockEntity.DoorStyle.RENDERED || core.clientRects().isEmpty() || core.getLevel() == null) {
            return;
        }
        double visual = core.visualProgress(partialTick);
        if (visual <= 0) {
            return;
        }
        TextureAtlasSprite body = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(BODY);
        TextureAtlasSprite edge = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(EDGE);
        VertexConsumer vc = buffers.getBuffer(RenderType.solid());
        BlockPos origin = core.getBlockPos();
        boolean alongX = core.clientAlongX();
        int fixed = core.clientFixed();
        pose.pushPose();
        pose.translate(-origin.getX(), -origin.getY(), -origin.getZ());
        Matrix4f mat = pose.last().pose();
        Matrix3f nm = pose.last().normal();
        for (int[] r : core.clientRects()) {
            int n = r[1] - r[0] + 1;
            double left = Math.min(visual, (n + 1) / 2), right = Math.min(visual, n / 2);
            int light = LevelRenderer.getLightColor(core.getLevel(), alongX ? new BlockPos((r[0] + r[1]) / 2, r[2], fixed) : new BlockPos(fixed, r[2], (r[0] + r[1]) / 2));
            if (left > 0) {
                panel(vc, mat, nm, alongX, fixed, r[0], r[0] + left, r[2], r[3] + 1, true, body, edge, light);
            }
            if (right > 0) {
                panel(vc, mat, nm, alongX, fixed, r[1] + 1 - right, r[1] + 1, r[2], r[3] + 1, false, body, edge, light);
            }
        }
        pose.popPose();
    }

    /** One panel from u0 to u1 along the axis, y0 to y1 up, THICK deep either side of the plane's middle. */
    private static void panel(VertexConsumer vc, Matrix4f mat, Matrix3f nm, boolean alongX, int fixed, double u0, double u1, double y0, double y1, boolean leftDoor,
                              TextureAtlasSprite body, TextureAtlasSprite edge, int light) {
        double f0 = fixed + 0.5 - THICK, f1 = fixed + 0.5 + THICK;
        double x0 = alongX ? u0 : f0, x1 = alongX ? u1 : f1, z0 = alongX ? f0 : u0, z1 = alongX ? f1 : u1;
        // Faces across the plane (the big ones), tiled per block.
        tiled(vc, mat, nm, body, light, x0, x1, y0, y1, z0, z1, alongX ? 'n' : 'w');
        tiled(vc, mat, nm, body, light, x0, x1, y0, y1, z0, z1, alongX ? 's' : 'e');
        tiled(vc, mat, nm, body, light, x0, x1, y0, y1, z0, z1, 'u');
        tiled(vc, mat, nm, body, light, x0, x1, y0, y1, z0, z1, 'd');
        // The two ends: the one toward the middle carries the hazard stripe.
        char inner = alongX ? (leftDoor ? 'e' : 'w') : (leftDoor ? 's' : 'n');
        char outer = alongX ? (leftDoor ? 'w' : 'e') : (leftDoor ? 'n' : 's');
        tiled(vc, mat, nm, edge, light, x0, x1, y0, y1, z0, z1, inner);
        tiled(vc, mat, nm, body, light, x0, x1, y0, y1, z0, z1, outer);
    }

    /** One face of the box, split into block-sized tiles so the texture repeats per block. */
    private static void tiled(VertexConsumer vc, Matrix4f mat, Matrix3f nm, TextureAtlasSprite sprite, int light,
                              double x0, double x1, double y0, double y1, double z0, double z1, char face) {
        // a = the face's horizontal span, b = its vertical span (y, or z for the top and bottom)
        double a0, a1, b0, b1;
        switch (face) {
            case 'n', 's' -> { a0 = x0; a1 = x1; b0 = y0; b1 = y1; }
            case 'w', 'e' -> { a0 = z0; a1 = z1; b0 = y0; b1 = y1; }
            default -> { a0 = x0; a1 = x1; b0 = z0; b1 = z1; }
        }
        for (double a = Math.floor(a0); a < a1; a += 1) {
            double ta0 = Math.max(a, a0), ta1 = Math.min(a + 1, a1);
            for (double b = Math.floor(b0); b < b1; b += 1) {
                double tb0 = Math.max(b, b0), tb1 = Math.min(b + 1, b1);
                float su0 = sprite.getU((float) ((ta0 - a) * 16)), su1 = sprite.getU((float) ((ta1 - a) * 16));
                float sv0 = sprite.getV((float) ((1 - (tb1 - b)) * 16)), sv1 = sprite.getV((float) ((1 - (tb0 - b)) * 16));
                quad(vc, mat, nm, light, face, ta0, ta1, tb0, tb1, x0, x1, y0, y1, z0, z1, su0, su1, sv0, sv1);
            }
        }
    }

    private static void quad(VertexConsumer vc, Matrix4f mat, Matrix3f nm, int light, char face, double a0, double a1, double b0, double b1,
                             double x0, double x1, double y0, double y1, double z0, double z1, float u0, float u1, float v0, float v1) {
        switch (face) {
            case 'n' -> { // looking from the north: x runs right to left
                v(vc, mat, nm, light, a1, b0, z0, u0, v1, 0, 0, -1); v(vc, mat, nm, light, a0, b0, z0, u1, v1, 0, 0, -1);
                v(vc, mat, nm, light, a0, b1, z0, u1, v0, 0, 0, -1); v(vc, mat, nm, light, a1, b1, z0, u0, v0, 0, 0, -1);
            }
            case 's' -> {
                v(vc, mat, nm, light, a0, b0, z1, u0, v1, 0, 0, 1); v(vc, mat, nm, light, a1, b0, z1, u1, v1, 0, 0, 1);
                v(vc, mat, nm, light, a1, b1, z1, u1, v0, 0, 0, 1); v(vc, mat, nm, light, a0, b1, z1, u0, v0, 0, 0, 1);
            }
            case 'w' -> { // looking from the west: z runs left to right
                v(vc, mat, nm, light, x0, b0, a0, u0, v1, -1, 0, 0); v(vc, mat, nm, light, x0, b0, a1, u1, v1, -1, 0, 0);
                v(vc, mat, nm, light, x0, b1, a1, u1, v0, -1, 0, 0); v(vc, mat, nm, light, x0, b1, a0, u0, v0, -1, 0, 0);
            }
            case 'e' -> {
                v(vc, mat, nm, light, x1, b0, a1, u0, v1, 1, 0, 0); v(vc, mat, nm, light, x1, b0, a0, u1, v1, 1, 0, 0);
                v(vc, mat, nm, light, x1, b1, a0, u1, v0, 1, 0, 0); v(vc, mat, nm, light, x1, b1, a1, u0, v0, 1, 0, 0);
            }
            case 'u' -> {
                v(vc, mat, nm, light, a0, y1, b1, u0, v1, 0, 1, 0); v(vc, mat, nm, light, a1, y1, b1, u1, v1, 0, 1, 0);
                v(vc, mat, nm, light, a1, y1, b0, u1, v0, 0, 1, 0); v(vc, mat, nm, light, a0, y1, b0, u0, v0, 0, 1, 0);
            }
            default -> {
                v(vc, mat, nm, light, a0, y0, b0, u0, v1, 0, -1, 0); v(vc, mat, nm, light, a1, y0, b0, u1, v1, 0, -1, 0);
                v(vc, mat, nm, light, a1, y0, b1, u1, v0, 0, -1, 0); v(vc, mat, nm, light, a0, y0, b1, u0, v0, 0, -1, 0);
            }
        }
    }

    private static void v(VertexConsumer vc, Matrix4f mat, Matrix3f nm, int light, double x, double y, double z, float u, float v, float nx, float ny, float nz) {
        vc.vertex(mat, (float) x, (float) y, (float) z).color(255, 255, 255, 255).uv(u, v).uv2(light).normal(nm, nx, ny, nz).endVertex();
    }
}
