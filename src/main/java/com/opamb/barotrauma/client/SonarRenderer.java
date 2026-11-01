package com.opamb.barotrauma.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.opamb.barotrauma.blockentity.SonarBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

import java.util.List;

/**
 * 3D 声纳全息。
 *
 * <p>把点云画成悬浮在声纳台上方的缩微体素云：越近越亮（青绿），越远越暗（蓝）。
 * 底下画两圈距离环 + 一条旋转扫描线。渲染用无贴图的 {@link RenderType#debugQuads()} /
 * {@link RenderType#lines()}，不需要额外材质。</p>
 */
public class SonarRenderer implements BlockEntityRenderer<SonarBlockEntity> {

    /** 世界格 → 全息格。半径 40 格压到 0.9 格。 */
    private static final float SCALE = 0.9F / SonarBlockEntity.RADIUS;
    private static final float Y_SQUASH = 0.5F;
    private static final float DOT = 0.035F;

    public SonarRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(SonarBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int packedLight, int packedOverlay) {
        List<BlockPos> points = be.points();
        if (points.isEmpty()) {
            return;
        }
        BlockPos origin = be.getBlockPos();

        pose.pushPose();
        pose.translate(0.5D, 1.25D, 0.5D);
        Matrix4f matrix = pose.last().pose();

        VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
        for (BlockPos point : points) {
            float x = (point.getX() - origin.getX()) * SCALE;
            float y = (point.getY() - origin.getY()) * SCALE * Y_SQUASH;
            float z = (point.getZ() - origin.getZ()) * SCALE;

            float t = Mth.clamp(Mth.sqrt(x * x + y * y + z * z) / 0.9F, 0.0F, 1.0F);
            float r = Mth.lerp(t, 0.45F, 0.08F);
            float g = Mth.lerp(t, 1.00F, 0.45F);
            float b = Mth.lerp(t, 0.85F, 0.70F);
            float a = Mth.lerp(t, 0.60F, 0.30F);

            cube(quads, matrix, x, y, z, DOT, r, g, b, a);
        }

        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        float bottom = -SonarBlockEntity.RADIUS * SCALE * Y_SQUASH;
        ring(lines, matrix, bottom, 0.45F, 0.75F, 0.9F, 0.55F);
        ring(lines, matrix, bottom, 0.90F, 0.55F, 0.8F, 0.45F);

        long time = be.getLevel() == null ? 0L : be.getLevel().getGameTime();
        float angle = (time % 160L) / 160.0F * Mth.TWO_PI;
        float ex = Mth.cos(angle) * 0.9F;
        float ez = Mth.sin(angle) * 0.9F;
        line(lines, matrix, 0.0F, bottom, 0.0F, ex, bottom, ez, 0.35F, 1.0F, 0.8F, 0.75F);

        pose.popPose();
    }

    /** 一个极小的立方体（只画顶面 + 两个侧面，点云密度够，省一半顶点）。 */
    private static void cube(VertexConsumer vc, Matrix4f m, float x, float y, float z, float s,
                             float r, float g, float b, float a) {
        // top
        quad(vc, m, x - s, y + s, z - s, x + s, y + s, z - s, x + s, y + s, z + s, x - s, y + s, z + s, r, g, b, a);
        // +x
        quad(vc, m, x + s, y - s, z - s, x + s, y + s, z - s, x + s, y + s, z + s, x + s, y - s, z + s, r, g, b, a);
        // +z
        quad(vc, m, x - s, y - s, z + s, x + s, y - s, z + s, x + s, y + s, z + s, x - s, y + s, z + s, r, g, b, a);
    }

    private static void quad(VertexConsumer vc, Matrix4f m,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3,
                             float r, float g, float b, float a) {
        vc.vertex(m, x0, y0, z0).color(r, g, b, a).endVertex();
        vc.vertex(m, x1, y1, z1).color(r, g, b, a).endVertex();
        vc.vertex(m, x2, y2, z2).color(r, g, b, a).endVertex();
        vc.vertex(m, x3, y3, z3).color(r, g, b, a).endVertex();
    }

    private static void ring(VertexConsumer vc, Matrix4f m, float y, float radius, float r, float g, float b) {
        int segments = 48;
        for (int i = 0; i < segments; i++) {
            float a0 = (float) (i * Math.PI * 2.0 / segments);
            float a1 = (float) ((i + 1) * Math.PI * 2.0 / segments);
            line(vc, m, Mth.cos(a0) * radius, y, Mth.sin(a0) * radius,
                    Mth.cos(a1) * radius, y, Mth.sin(a1) * radius, r, g, b, 0.35F);
        }
    }

    private static void line(VertexConsumer vc, Matrix4f m,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float r, float g, float b, float a) {
        vc.vertex(m, x0, y0, z0).color(r, g, b, a).normal(0.0F, 1.0F, 0.0F).endVertex();
        vc.vertex(m, x1, y1, z1).color(r, g, b, a).normal(0.0F, 1.0F, 0.0F).endVertex();
    }

    @Override
    public int getViewDistance() {
        return 96;
    }
}
