package com.opamb.barotrauma.client;

import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.world.ModDimensions;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.BubbleParticle;
import net.minecraft.client.particle.SuspendedParticle;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 干掉深海维度里的水下粒子。
 *
 * <p>水柱里的那些小颗粒来自 {@code WaterFluid#animateTick}：每帧在玩家附近随机抽几个水方块
 * 生成 {@code minecraft:underwater} 粒子。1000 米高的水柱里这个量非常可观，所以这里把
 * {@code underwater} / {@code bubble} 两个 Provider 换掉——只在深海维度返回 {@code null}
 * （{@code ParticleEngine} 遇到 null 就不会添加），其它维度照旧走原版实现。</p>
 */
@Mod.EventBusSubscriber(modid = BarotraumaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class DeepSeaParticles {

    private DeepSeaParticles() {
    }

    @SubscribeEvent
    public static void onRegisterProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ParticleTypes.UNDERWATER, spriteSet -> {
            SuspendedParticle.UnderwaterProvider vanilla = new SuspendedParticle.UnderwaterProvider(spriteSet);
            return (type, level, x, y, z, xs, ys, zs) ->
                    isDeep(level) ? null : vanilla.createParticle(type, level, x, y, z, xs, ys, zs);
        });

        event.registerSpriteSet(ParticleTypes.BUBBLE, spriteSet -> {
            BubbleParticle.Provider vanilla = new BubbleParticle.Provider(spriteSet);
            return (type, level, x, y, z, xs, ys, zs) ->
                    isDeep(level) ? null : vanilla.createParticle(type, level, x, y, z, xs, ys, zs);
        });
    }

    private static boolean isDeep(ClientLevel level) {
        return level.dimension().equals(ModDimensions.THE_DEEP);
    }
}
