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

    private UnderwaterMovement() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.screen != null
                || player.isSpectator() || player.isPassenger()) {
            return;
        }
        if (!OceanEnvironment.isSubmerged(mc.level, player.getEyePosition())) {
            return;
        }
        Vec3 motion = player.getDeltaMovement();
        double y = motion.y * 0.82D - 0.018D;
        if (mc.options.keyJump.isDown()) {
            y += 0.085D;
        }
        if (mc.options.keyShift.isDown()) {
            y -= 0.070D;
        }
        player.setDeltaMovement(motion.x * 0.82D, y, motion.z * 0.82D);
        player.resetFallDistance();
    }
}
