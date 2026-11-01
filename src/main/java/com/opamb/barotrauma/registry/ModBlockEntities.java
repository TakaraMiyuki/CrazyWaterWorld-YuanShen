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

    public static final RegistryObject<BlockEntityType<ShipCoreBlockEntity>> SHIP_CORE =
            BLOCK_ENTITIES.register("ship_core",
                    () -> BlockEntityType.Builder.of(ShipCoreBlockEntity::new, ModShipBlocks.SHIP_CORE.get()).build(null));

    private ModBlockEntities() {
    }

    public static void register(IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
    }
}
