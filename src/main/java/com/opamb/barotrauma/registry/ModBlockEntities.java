package com.opamb.barotrauma.registry;

import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.blockentity.BulkheadDoorBlockEntity;
import com.opamb.barotrauma.blockentity.DrainPumpBlockEntity;
import com.opamb.barotrauma.blockentity.HatchBlockEntity;
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

    public static final RegistryObject<BlockEntityType<DrainPumpBlockEntity>> DRAIN_PUMP =
            BLOCK_ENTITIES.register("drain_pump", () -> BlockEntityType.Builder
                    .of(DrainPumpBlockEntity::new, ModBlocks.DRAIN_PUMP.get()).build(null));

    /** 3 格宽舱口；只有中间段有方块实体，渲染整扇模型。 */
    public static final RegistryObject<BlockEntityType<HatchBlockEntity>> HATCH =
            BLOCK_ENTITIES.register("hatch", () -> BlockEntityType.Builder
                    .of(HatchBlockEntity::new, ModShipBlocks.HATCH.get()).build(null));

    /** 3 格高舱壁门；只有最下面一段有方块实体，渲染整扇模型。 */
    public static final RegistryObject<BlockEntityType<BulkheadDoorBlockEntity>> BULKHEAD_DOOR =
            BLOCK_ENTITIES.register("bulkhead_door", () -> BlockEntityType.Builder
                    .of(BulkheadDoorBlockEntity::new, ModShipBlocks.BULKHEAD_DOOR.get()).build(null));

    // 船核心方块已经合并进声纳台（SonarBlockEntity extends ShipCoreBlockEntity），
    // 不再有独立的 ship_core 方块实体。

    private ModBlockEntities() {
    }

    public static void register(IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
    }
}
