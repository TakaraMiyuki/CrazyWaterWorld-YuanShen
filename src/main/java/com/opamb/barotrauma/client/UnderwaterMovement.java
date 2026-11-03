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
        if (!OceanEnvironment.isSubmerged(mc.level, player.getEyePosition())) {
            return;
        }
        Vec3 motion = player.getDeltaMovement();
        double y;
        if (mc.screen != null) {
            // 打开 GUI 时原版不再处理移动输入、重力照常拉人；这里保持悬浮，
            // 否则在声纳/背包界面里会直接沉底。
            y = 0.0D;
        } else if (mc.options.keyJump.isDown()) {
            // 直接给一个明确的上浮速度；不要在原版重力后的负速度上做加法。
            y = SWIM_VERTICAL_SPEED;
        } else if (mc.options.keyShift.isDown()) {
            y = -SWIM_VERTICAL_SPEED;
        } else {
            // 松开按键后保持当前深度：不上浮也不下沉。
            y = 0.0D;
        }
        player.setDeltaMovement(motion.x * 0.78D, y, motion.z * 0.78D);
        player.resetFallDistance();
    }
}
