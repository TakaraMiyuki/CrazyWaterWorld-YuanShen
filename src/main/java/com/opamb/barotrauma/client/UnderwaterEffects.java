package com.opamb.barotrauma.client;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.world.OceanEnvironment;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer.FogMode;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/**
 * 空气海域的水下视觉：海水颜色、随深度缩短的视距、屏幕色调。
 *
 * <p>海里没有真实水，所以这些效果完全由相机位置 + {@link OceanEnvironment} 决定；
 * 哨站和未进水船舱不会把整块屏幕染成水下。</p>
 *
 * <p><b>“外面蓝色的海”靠位置决定，不看视线。</b> 深海维度没有真实水，空旷处的“海”
 * 就是背景色：`AFTER_SKY` 用相机相对的大方块把天空铺成海水色（地形画在它前面，
 * 所以舱内墙壁不会被染色），雾色也按相机位置设置为水色。之前按“视线中心是否看到水”
 * 来开关雾，导致用余光看海时效果消失、必须直视才触发。</p>
 */
@Mod.EventBusSubscriber(modid = BarotraumaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class UnderwaterEffects {

    /**
     * 视距：干燥区（船舱/哨站）和真正在水里用同一套。
     *
     * <p>之前分两套（水下 1.5~22、干燥区 48~112），同一个海从室内看和从水里看明显不同。
     * 现在统一成近处清晰、远处淡入水色；近平面 20 大于船舱内腔尺寸，所以船内不会起雾。</p>
     */
    private static final float SEA_FOG_NEAR = 20.0F;
    private static final float SEA_FOG_FAR = 72.0F;

    /** 上一次的浸没判定，只在切换时打日志，方便核对舱内滤镜。 */
    private static boolean lastSubmerged;

    private UnderwaterEffects() {
    }

    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Camera camera = event.getCamera();
        Vec3 eye = camera.getPosition();
        // 只要在深海的水柱范围内就用水色——包括站在未进水的船舱/哨站里。
        // 雾色同时是世界背景色：窗外/舱外空旷处因此永远是海水蓝，和不看它无关。
        if (!OceanEnvironment.isInOcean(mc.level, eye)) {
            return;
        }
        float depth = depthAt(eye.y);
        event.setRed(0.012F + 0.025F * depth);
        event.setGreen(0.075F + 0.055F * depth);
        event.setBlue(0.115F + 0.075F * depth);
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (event.getMode() != FogMode.FOG_TERRAIN) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Camera camera = event.getCamera();
        Vec3 eye = camera.getPosition();
        if (!OceanEnvironment.isInOcean(mc.level, eye)) {
            return;
        }
        // 室内和海里用同一套雾：近处保持清晰，远处（海水/地形）淡入水色。
        // 这样同一个海从舱内看和潜到水里看不会是两个画面。
        float depth = depthAt(eye.y);
        float far = SEA_FOG_FAR - 24.0F * depth;
        event.setNearPlaneDistance(SEA_FOG_NEAR);
        event.setFarPlaneDistance(Math.max(SEA_FOG_NEAR + 16.0F, far));
        event.setFogShape(FogShape.SPHERE);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        Vec3 pos = mc.gameRenderer.getMainCamera().getPosition();
        boolean submerged = OceanEnvironment.isSubmerged(mc.level, pos);
        if (submerged != lastSubmerged) {
            lastSubmerged = submerged;
            BarotraumaMod.LOGGER.info("[water] 客户端浸没切换：submerged={} pos=({},{},{})",
                    submerged, pos.x, pos.y, pos.z);
        }
        if (!submerged) {
            return;
        }
        float depth = depthAt(pos.y);
        int alpha = 0x28 + (int) (0x20 * depth);
        int color = (alpha << 24) | 0x0A1A28;
        event.getGuiGraphics().fill(0, 0,
                mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), color);
    }

    /**
     * 海水背景：天空之后、地形之前，用相机相对的大方块铺一层水色。
     *
     * <p>深海维度没有真正的海水方块，空旷处的“海”其实是天空/清屏色。原版天空是黑的，
     * 只靠“视线中心看到水才加雾”的话，用余光看海就什么都没有。这里把天空直接换成
     * 海水色：它在所有地形后面，所以船内墙壁、哨站内部不会被染色，而窗外任何角度
     * 都是蓝的。半径 16 保证相机在方块内部、任意视线都能打到面，且远小于投影远平面。</p>
     */
    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        Vec3 eye = event.getCamera().getPosition();
        if (!OceanEnvironment.isInOcean(mc.level, eye)) {
            return;
        }
        float depth = depthAt(eye.y);
        drawOceanBackdrop(event,
                0.012F + 0.025F * depth,
                0.075F + 0.055F * depth,
                0.115F + 0.075F * depth);
    }

    private static final float BACKDROP_RADIUS = 16.0F;

    /** 复用的背景 builder（Tesselator 模式：每帧 begin/end；direct 内存不再等 GC）。 */
    private static final BufferBuilder BACKDROP_BUILDER = new BufferBuilder(1024);

    private static void drawOceanBackdrop(RenderLevelStageEvent event, float r, float g, float b) {
        float v = BACKDROP_RADIUS;
        if (BACKDROP_BUILDER.building()) {
            BACKDROP_BUILDER.discard();
        }
        BufferBuilder builder = BACKDROP_BUILDER;
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        // 六面立方体，相机在中心；不剔除、不测深度，并集覆盖整个屏幕。
        // 顶点颜色按世界 Y 做一个上下渐变：上方亮一点、下方暗一点，避免整片死蓝色。
        backdropQuad(builder, r, g, b,
                v, -v, -v, v, -v, v, v, v, v, v, v, -v);      // +X
        backdropQuad(builder, r, g, b,
                -v, -v, -v, -v, v, -v, -v, v, v, -v, -v, v);  // -X
        backdropQuad(builder, r, g, b,
                -v, v, -v, v, v, -v, v, v, v, -v, v, v);      // +Y
        backdropQuad(builder, r, g, b,
                -v, -v, -v, -v, -v, v, v, -v, v, v, -v, -v);  // -Y
        backdropQuad(builder, r, g, b,
                -v, -v, v, v, -v, v, v, v, v, -v, v, v);      // +Z
        backdropQuad(builder, r, g, b,
                -v, -v, -v, -v, v, -v, v, v, -v, v, -v, -v);  // -Z
        BufferBuilder.RenderedBuffer rendered = builder.end();

        Matrix4f pose = event.getPoseStack().last().pose();
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        com.mojang.blaze3d.vertex.VertexSorting previousSorting = RenderSystem.getVertexSorting();
        float[] previousColor = RenderSystem.getShaderColor();
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.setIdentity();
        modelView.mulPoseMatrix(pose);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(event.getProjectionMatrix(),
                com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.disableBlend();
        try {
            BufferUploader.drawWithShader(rendered);
        } finally {
            RenderSystem.enableBlend();
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.setShaderColor(previousColor[0], previousColor[1],
                    previousColor[2], previousColor[3]);
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
        }
    }

    private static void backdropQuad(BufferBuilder builder, float r, float g, float b,
                                     float x1, float y1, float z1, float x2, float y2, float z2,
                                     float x3, float y3, float z3, float x4, float y4, float z4) {
        backdropVertex(builder, r, g, b, x1, y1, z1);
        backdropVertex(builder, r, g, b, x2, y2, z2);
        backdropVertex(builder, r, g, b, x3, y3, z3);
        backdropVertex(builder, r, g, b, x4, y4, z4);
    }

    private static void backdropVertex(BufferBuilder builder, float r, float g, float b,
                                       float x, float y, float z) {
        // 上下渐变：horizon (y=0) 保持雾色，越往上越亮、越往下越暗。
        float shade = 1.0F + 0.22F * (y / BACKDROP_RADIUS);
        builder.vertex(x, y, z)
                .color(Math.min(1.0F, r * shade), Math.min(1.0F, g * shade),
                        Math.min(1.0F, b * shade), 1.0F)
                .endVertex();
    }

    private static float depthAt(double y) {
        return (float) Math.min(1.0D, Math.max(0.0D, -y / 1000.0D));
    }
}
