package com.opamb.barotrauma.registry;

import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.blockentity.ShipCoreBlockEntity;
import com.opamb.barotrauma.blockentity.SonarBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 方块实体注册表。
 */
public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, BarotraumaMod.MOD_ID);

    public static final RegistryObject<BlockEntityType<SonarBlockEntity>> SONAR =
            BLOCK_ENTITIES.register("sonar",
                    () -> BlockEntityType.Builder.of(SonarBlockEntity::new, ModShipBlocks.SONAR.get()).build(null));

    // 船核心方块已经合并进声纳台（SonarBlockEntity extends ShipCoreBlockEntity），
    // 不再有独立的 ship_core 方块实体。

    private ModBlockEntities() {
    }

    public static void register(IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
    }
}
