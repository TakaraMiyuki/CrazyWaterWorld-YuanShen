package com.opamb.barotrauma.registry;

import com.opamb.barotrauma.BarotraumaMod;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 粒子注册：目前只有破口冒泡用的放大版气泡。 */
public final class ModParticles {

    public static final DeferredRegister<ParticleType<?>> PARTICLES =
            DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, BarotraumaMod.MOD_ID);

    /**
     * 破口气泡。行为照抄原版 BubbleParticle（上浮 + 阻尼），但尺寸放大到能看清；
     * 贴图由 {@code assets/barotrauma/particles/ship_bubble.json} 指定。
     */
    public static final RegistryObject<SimpleParticleType> SHIP_BUBBLE =
            PARTICLES.register("ship_bubble", () -> new SimpleParticleType(false));

    /** 空气舱破口喷出的细水珠（喷淋头效果）。 */
    public static final RegistryObject<SimpleParticleType> SHIP_SPRAY =
            PARTICLES.register("ship_spray", () -> new SimpleParticleType(false));

    private ModParticles() {
    }

    public static void register(IEventBus modBus) {
        PARTICLES.register(modBus);
    }
}
