package com.mrgregles.bsp_teleport.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.mrgregles.bsp_teleport.access.AccessGrant;
import com.mrgregles.bsp_teleport.block.AccessBoardBlock;
import com.mrgregles.bsp_teleport.block.AccessBoardBlockEntity;
import com.mrgregles.bsp_teleport.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Draws a joined Access Board (D85, D89): only the bottom-left panel draws, on a canvas of 64 pixels per panel, in one
 * of three styles: Bars (a row per server with a bar for home players), Tiles (a tile per server with the home count
 * large) or Columns (a column chart). The counts come from the client's access sync, so every player sees the same numbers.
 */
public class AccessBoardRenderer implements BlockEntityRenderer<AccessBoardBlockEntity> {
    private static final int PX = 64, BG = 0xFF070A0E, TQ = 0xFF19D3B0, GOLD = 0xFFFFD23A, TEXT = 0xFFE8EAF0, MUTED = 0xFF9AA3B5, BAR_BG = 0xFF1C2230, LINE = 0xFF3B434C;
    private static final int[] COLOURS = {0xFFB02E26, 0xFFF9801D, 0xFFFF9F1C, 0xFFFFD23A, 0xFFFED83D, 0xFF80C71F, 0xFF5E7C16, 0xFF8FE0A4, 0xFF19D3B0, 0xFF169C9C,
            0xFFBFEFFF, 0xFF3AB3DA, 0xFF3C44AA, 0xFFB58CFF, 0xFF8932B8, 0xFFC74EBD, 0xFFF38BAA, 0xFFFF6B8A, 0xFF835432, 0xFF9D9D97};

    private final Font font;
    private PoseStack pose;
    private MultiBufferSource buffers;

    public AccessBoardRenderer(BlockEntityRendererProvider.Context ctx) {
        this.font = ctx.getFont();
    }

    @Override
    public boolean shouldRenderOffScreen(AccessBoardBlockEntity board) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 96;
    }

    private void rect(float x0, float y0, float x1, float y1, int argb) {
        VertexConsumer vc = buffers.getBuffer(RenderType.textBackground());
        Matrix4f m = pose.last().pose();
        int a = argb >>> 24, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        float[][] quad = {{x0, y0}, {x0, y1}, {x1, y1}, {x1, y0}};
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < 4; i++) {
                float[] p = quad[pass == 0 ? i : 3 - i];
                vc.vertex(m, p[0], p[1], 0).color(r, g, b, a).uv2(LightTexture.FULL_BRIGHT).endVertex();
            }
        }
    }

    /** Text {@code px} pixels tall at (x, y) vertical centre, shrunk to fit {@code maxWidth}; align 0 left, 0.5 centre, 1 right. */
    private void text(String s, float x, float y, float px, int colour, float align, float maxWidth) {
        Component c = Component.literal(s);
        float w = font.width(c), k = px / 8f;
        if (w * k > maxWidth && w > 0) {
            k = maxWidth / w;
        }
        pose.pushPose();
        pose.translate(x, y, 0.4f);
        pose.scale(k, k, 1f);
        font.drawInBatch(c, -w * align, -4f, colour, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        pose.popPose();
    }

    private void icon(Level level, int token, float cx, float cy, float size) {
        if (token < 1 || token > ModItems.ACCESS_TOKENS.length) {
            return;
        }
        ItemStack stack = new ItemStack(ModItems.ACCESS_TOKENS[token - 1].get());
        pose.pushPose();
        pose.translate(cx, cy, 1.2f);
        pose.scale(size, -size, size * 0.02f);
        Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.GUI, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, pose, buffers, level, 0);
        pose.popPose();
    }

    private static int colourOf(int token) {
        return token >= 1 && token <= COLOURS.length ? COLOURS[token - 1] : TQ;
    }

    @Override
    public void render(AccessBoardBlockEntity board, float partialTick, PoseStack pose, MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Level level = board.getLevel();
        if (level == null) {
            return;
        }
        Direction facing = board.getBlockState().getValue(AccessBoardBlock.FACING);
        if (!AccessBoardBlock.origin(level, board.getBlockPos(), facing).equals(board.getBlockPos())) {
            return;
        }
        int[] wh = AccessBoardBlock.size(level, board.getBlockPos(), facing);
        float w = wh[0] * PX, h = wh[1] * PX;
        this.pose = pose;
        this.buffers = buffers;

        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        pose.translate(-0.5, wh[1] - 0.5, -0.3125 + 0.004);
        pose.scale(1f / PX, -1f / PX, 1f / PX);

        float u = Math.min(wh[0] / 5f, wh[1] / 2.2f) * board.size() / 100f;
        rect(0, 0, w, h, BG);
        float th = 22 * u, top = th + 6, area = h - top - 4;
        text(board.title(), w / 2, th / 2 + 3, 14 * u, TQ, 0.5f, w - 8);
        pose.translate(0, 0, 0.2f);
        rect(2, th + 3, w - 2, th + 4.5f, TQ);

        List<ClientAccess.Count> counts = ClientAccess.counts();
        if (!ClientAccess.enabled() || counts.isEmpty()) {
            text(Component.translatable("screen.bsp_teleport.board.empty").getString(), w / 2, top + area / 2, 10 * u, MUTED, 0.5f, w - 12);
        } else if (board.style() == AccessBoardBlockEntity.STYLE_TILES) {
            tiles(level, counts, w, top, area, u);
        } else if (board.style() == AccessBoardBlockEntity.STYLE_COLUMNS) {
            columns(level, counts, w, top, area, u);
        } else {
            bars(level, counts, w, top, area, u);
        }
        pose.popPose();
    }

    private static int max(List<ClientAccess.Count> counts) {
        int max = 1;
        for (ClientAccess.Count c : counts) {
            max = Math.max(max, c.home());
        }
        return max;
    }

    private String sub(ClientAccess.Count c) {
        return Component.translatable("screen.bsp_teleport.board.sub", c.full(), c.rent()).getString();
    }

    private void bars(Level level, List<ClientAccess.Count> counts, float w, float top, float area, float u) {
        float rh = Math.min(20 * u, area / counts.size());
        int max = max(counts);
        float y = top;
        for (ClientAccess.Count c : counts) {
            int token = ClientAccess.tokenFor(AccessGrant.Kind.SERVER, c.server());
            float ih = rh * 0.6f;
            icon(level, token, 4 + ih / 2, y + rh / 2, ih);
            float nx = 6 + ih + 2, numW = 22 * u;
            text(c.server(), nx, y + rh * 0.3f, rh * 0.38f, TEXT, 0f, w - nx - numW - 6);
            float bx0 = nx, bx1 = w - numW - 6, by0 = y + rh * 0.55f, by1 = y + rh * 0.72f;
            rect(bx0, by0, bx1, by1, BAR_BG);
            rect(bx0, by0, bx0 + (bx1 - bx0) * c.home() / max, by1, colourOf(token));
            text(sub(c), nx, y + rh * 0.87f, rh * 0.22f, MUTED, 0f, bx1 - bx0);
            text(String.valueOf(c.home()), w - 4, y + rh / 2, rh * 0.5f, GOLD, 1f, numW);
            y += rh;
        }
    }

    private void tiles(Level level, List<ClientAccess.Count> counts, float w, float top, float area, float u) {
        int n = counts.size(), cols = n <= 2 ? n : n <= 4 ? 2 : 3, rows = (n + cols - 1) / cols;
        float tw = (w - 8) / cols, thh = area / rows;
        for (int i = 0; i < n; i++) {
            ClientAccess.Count c = counts.get(i);
            float x0 = 4 + (i % cols) * tw, y0 = top + (i / cols) * thh;
            rect(x0 + 1, y0 + 1, x0 + tw - 1, y0 + thh - 1, LINE);
            rect(x0 + 2, y0 + 2, x0 + tw - 2, y0 + thh - 2, BG);
            int token = ClientAccess.tokenFor(AccessGrant.Kind.SERVER, c.server());
            float cx = x0 + tw / 2;
            icon(level, token, cx, y0 + thh * 0.22f, thh * 0.26f);
            text(String.valueOf(c.home()), cx, y0 + thh * 0.52f, thh * 0.34f, GOLD, 0.5f, tw - 6);
            text(c.server(), cx, y0 + thh * 0.74f, thh * 0.15f, TEXT, 0.5f, tw - 6);
            text(sub(c), cx, y0 + thh * 0.88f, thh * 0.11f, MUTED, 0.5f, tw - 6);
        }
    }

    private void columns(Level level, List<ClientAccess.Count> counts, float w, float top, float area, float u) {
        int n = counts.size(), max = max(counts);
        float cw = (w - 8) / n, base = top + area * 0.78f, chartH = area * 0.62f;
        for (int i = 0; i < n; i++) {
            ClientAccess.Count c = counts.get(i);
            float cx = 4 + cw * (i + 0.5f);
            int token = ClientAccess.tokenFor(AccessGrant.Kind.SERVER, c.server());
            float bh = chartH * c.home() / max;
            rect(cx - cw * 0.3f, base - bh, cx + cw * 0.3f, base, colourOf(token));
            text(String.valueOf(c.home()), cx, base - bh - area * 0.06f, area * 0.1f, GOLD, 0.5f, cw);
            icon(level, token, cx, base + area * 0.08f, area * 0.12f);
            String name = c.server().startsWith("BSP-") ? c.server().substring(4) : c.server();
            text(name, cx, base + area * 0.19f, area * 0.08f, TEXT, 0.5f, cw - 2);
        }
    }
}
