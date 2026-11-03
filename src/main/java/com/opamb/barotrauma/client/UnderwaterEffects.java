package com.opamb.barotrauma.client;

import com.mojang.blaze3d.shaders.FogShape;
import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.world.OceanEnvironment;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer.FogMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 空气海域的水下视觉：蓝绿色雾、随深度缩短的视距、屏幕色调。
 *
 * <p>海里没有真实水，所以这些效果完全由相机位置 + {@link OceanEnvironment} 决定；
 * 哨站和未进水船舱会自动排除，不会把室内染成水下。</p>
 *
 * <p>站在干燥区里往窗外看时，相机本身不算浸没，但视线穿过玻璃后就是水；
 * 这时按“看到水的距离”把水下雾推到窗外，室内保持清晰、窗外仍然像水。</p>
 */
@Mod.EventBusSubscriber(modid = BarotraumaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class UnderwaterEffects {

    /** 找“看到水”的最大距离；再远就不做视线染色，避免每帧扫太远。 */
    private static final double MAX_LOOK_DISTANCE = 32.0D;

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
        if (!isSubmerged(mc.level, eye, camera)) {
            return;
        }
        float depth = depthAt(eye.y);
        event.setRed(0.008F + 0.020F * depth);
        event.setGreen(0.060F + 0.045F * depth);
        event.setBlue(0.090F + 0.070F * depth);
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
        boolean submerged = OceanEnvironment.isSubmerged(mc.level, eye);
        double waterDistance = submerged ? 0.0D : waterDistance(mc.level, eye, look(camera));
        if (!submerged && waterDistance < 0.0D) {
            return;
        }
        float depth = depthAt(eye.y);
        // 浅水 22 格、深水 12 格；雾从 1.5 格就开始，形成明显的“看不清远处”。
        float range = 22.0F - 10.0F * depth;
        if (submerged) {
            event.setNearPlaneDistance(1.5F);
            event.setFarPlaneDistance(range);
        } else {
            // 干燥区里看水：从看到水的位置开始雾化，窗户/舱内本身保持清晰。
            float near = (float) Math.max(1.5D, waterDistance);
            event.setNearPlaneDistance(near);
            event.setFarPlaneDistance(near + range);
        }
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
     * 雾/色调是否生效：相机浸没，或者相机在干燥区但视线能看到水。
     */
    private static boolean isSubmerged(Level level, Vec3 eye, Camera camera) {
        return OceanEnvironment.isSubmerged(level, eye)
                || waterDistance(level, eye, look(camera)) >= 0.0D;
    }

    /**
     * 从相机沿视线找第一个“水”采样点；被不透明方块挡住就停。
     *
     * <p>玻璃的 {@code canOcclude()} 是 false，所以能穿过窗户继续找；
     * 船壳、岩层会直接截断，避免隔着墙把室内也染蓝。</p>
     *
     * @return 到第一个水采样点的距离；看不到水返回 -1
     */
    private static double waterDistance(Level level, Vec3 eye, Vec3 look) {
        for (double distance = 1.0D; distance <= MAX_LOOK_DISTANCE; distance += 1.0D) {
            Vec3 probe = eye.add(look.scale(distance));
            BlockPos blockPos = BlockPos.containing(probe);
            if (!level.isLoaded(blockPos)) {
                return -1.0D;
            }
            BlockState state = level.getBlockState(blockPos);
            if (state.canOcclude()) {
                return -1.0D;
            }
            if (OceanEnvironment.isSubmerged(level, probe)) {
                return distance;
            }
        }
        return -1.0D;
    }

    private static Vec3 look(Camera camera) {
        var vector = camera.getLookVector();
        return new Vec3(vector.x(), vector.y(), vector.z());
    }

    private static float depthAt(double y) {
        return (float) Math.min(1.0D, Math.max(0.0D, -y / 1000.0D));
    }
}
