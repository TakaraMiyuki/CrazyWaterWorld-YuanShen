package com.opamb.barotrauma.client;

import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.world.OceanEnvironment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 空气海域里的玩家游泳。
 *
 * <p>不走原版 {@code isInWater} 假水路径——那条路会触发 VS2 的实体区块碰撞迭代异常。
 * 这里只在客户端给玩家施加水阻、缓慢下沉和跳跃上浮；服务端仍按客户端位置同步。</p>
 */
@Mod.EventBusSubscriber(modid = BarotraumaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class UnderwaterMovement {

    /** 上浮/下潜使用同一速度，避免手感不对称。 */
    private static final double SWIM_VERTICAL_SPEED = 0.12D;

    private UnderwaterMovement() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null
                || player.isSpectator() || player.isPassenger()) {
            return;
        }
        // 判定点取"脚上方 0.4 格"而不是眼睛，也不是脚底：
        //  - 用眼睛：水淹到胸口时还算干燥，沉不下去；
        //  - 用脚底：干燥舱室的水面就是舱底 y，脚正好等于它，会被判成"在水里"，
        //    于是干舱里也有粘滞的游泳感（用户实测"船内还没开就有漏水游泳的感觉"）。
        Vec3 sample = player.position().add(0.0D, 0.4D, 0.0D);
        boolean submergedFeet = OceanEnvironment.isSubmerged(mc.level, sample);
        if (!submergedFeet) {
            return;
        }
        Vec3 motion = player.getDeltaMovement();
        // 开 GUI（声纳台）时原版会 releaseAll 键位，KeyMapping.isDown() 恒为 false；
        // 这里直接读物理键盘，保证开着声纳按 Shift 也能下潜。
        boolean shiftDown = physicalKeyDown(mc, mc.options.keyShift);
        boolean jumpDown = physicalKeyDown(mc, mc.options.keyJump);
        double y;
        if (jumpDown) {
            // 直接给一个明确的上浮速度；不要在原版重力后的负速度上做加法。
            y = SWIM_VERTICAL_SPEED;
        } else if (shiftDown) {
            y = -SWIM_VERTICAL_SPEED;
        } else {
            // 松开按键后保持当前深度：不上浮也不下沉（开 GUI 时同理，但 Shift 仍然有效）。
            y = 0.0D;
        }
        player.setDeltaMovement(motion.x * 0.78D, y, motion.z * 0.78D);
        player.resetFallDistance();
    }

    /**
     * 直接问窗口"这个物理键现在按着没有"。
     *
     * <p>开 GUI 后 {@code KeyMapping.isDown()} 全是 false（{@code setScreen} 会
     * releaseAll），所以声纳台开着时按 Shift 原来完全没反应。</p>
     *
     * <p>左右 Shift 在 GLFW 里是两个不同的键：绑定读的是 {@code left.shift}，
     * 只查它会把"按右 Shift"当成没按。这里两种都认。</p>
     */
    private static boolean physicalKeyDown(Minecraft mc, net.minecraft.client.KeyMapping mapping) {
        try {
            com.mojang.blaze3d.platform.InputConstants.Key key = mapping.getKey();
            if (key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM) {
                long window = mc.getWindow().getWindow();
                int code = key.getValue();
                if (com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, code)) {
                    return true;
                }
                if (code == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT) {
                    return com.mojang.blaze3d.platform.InputConstants.isKeyDown(
                            window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT);
                }
                if (code == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT) {
                    return com.mojang.blaze3d.platform.InputConstants.isKeyDown(
                            window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT);
                }
            }
        } catch (Throwable ignored) {
            // 读不到就退回原版状态
        }
        return mapping.isDown();
    }
}
