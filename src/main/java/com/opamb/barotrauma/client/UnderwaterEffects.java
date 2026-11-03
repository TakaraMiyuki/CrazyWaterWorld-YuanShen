package com.opamb.barotrauma.client;

import com.mojang.blaze3d.shaders.FogShape;
import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.world.OceanEnvironment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer.FogMode;
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
 */
@Mod.EventBusSubscriber(modid = BarotraumaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class UnderwaterEffects {

    private UnderwaterEffects() {
    }

    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        Vec3 pos = event.getCamera().getPosition();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !OceanEnvironment.isSubmerged(mc.level, pos)) {
            return;
        }
        float depth = (float) Math.min(1.0D, Math.max(0.0D, -pos.y / 1000.0D));
        event.setRed(0.008F + 0.020F * depth);
        event.setGreen(0.060F + 0.045F * depth);
        event.setBlue(0.090F + 0.070F * depth);
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (event.getMode() != FogMode.FOG_TERRAIN) {
            return;
        }
        Vec3 pos = event.getCamera().getPosition();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !OceanEnvironment.isSubmerged(mc.level, pos)) {
            return;
        }
        float depth = (float) Math.min(1.0D, Math.max(0.0D, -pos.y / 1000.0D));
        // 浅水 22 格、深水 12 格；雾从 1.5 格就开始，形成明显的“看不清远处”。
        event.setNearPlaneDistance(1.5F);
        event.setFarPlaneDistance(22.0F - 10.0F * depth);
        event.setFogShape(FogShape.SPHERE);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null) {
            return;
        }
        if (!OceanEnvironment.isSubmerged(mc.level, mc.gameRenderer.getMainCamera().getPosition())) {
            return;
        }
        float depth = (float) Math.min(1.0D, Math.max(0.0D,
                -mc.gameRenderer.getMainCamera().getPosition().y / 1000.0D));
        int alpha = 0x28 + (int) (0x20 * depth);
        int color = (alpha << 24) | 0x0A1A28;
        event.getGuiGraphics().fill(0, 0,
                mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), color);
    }
}
