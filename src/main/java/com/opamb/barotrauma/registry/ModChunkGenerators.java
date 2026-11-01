package com.opamb.barotrauma.registry;

import com.mojang.serialization.Codec;
import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.world.DeepSeaChunkGenerator;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 区块生成器注册。
 *
 * <p>注册名 {@code barotrauma:deep_sea} 会被 dimension JSON 的
 * {@code generator.type} 引用。</p>
 */
public final class ModChunkGenerators {

    public static final DeferredRegister<Codec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, BarotraumaMod.MOD_ID);

    public static final RegistryObject<Codec<? extends ChunkGenerator>> DEEP_SEA =
            CHUNK_GENERATORS.register("deep_sea", () -> DeepSeaChunkGenerator.CODEC);

    private ModChunkGenerators() {
    }

    public static void register(IEventBus modBus) {
        CHUNK_GENERATORS.register(modBus);
    }
}
