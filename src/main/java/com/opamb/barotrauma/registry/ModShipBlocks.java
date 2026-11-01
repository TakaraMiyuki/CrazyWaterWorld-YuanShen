package com.opamb.barotrauma.registry;

import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.block.ShipCoreBlock;
import com.opamb.barotrauma.block.ShipDoorBlock;
import com.opamb.barotrauma.block.SonarBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 潜艇方块注册表：船体、舱门、七台机器。
 */
public final class ModShipBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, BarotraumaMod.MOD_ID);

    // ---- 船体 ----
    public static final RegistryObject<Block> HULL_GLASS = BLOCKS.register("hull_glass",
            () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_BLUE)
                    .strength(0.3F)
                    .sound(SoundType.GLASS)
                    .noOcclusion()));

    public static final RegistryObject<Block> HATCH = BLOCKS.register("hatch",
            () -> new ShipDoorBlock(machine()));

    public static final RegistryObject<Block> AIRLOCK_DOOR = BLOCKS.register("airlock_door",
            () -> new ShipDoorBlock(machine()));

    // ---- 机器 ----
    public static final RegistryObject<Block> NUCLEAR_REACTOR = BLOCKS.register("nuclear_reactor",
            () -> new Block(machine()));

    public static final RegistryObject<Block> OXYGEN_GENERATOR = BLOCKS.register("oxygen_generator",
            () -> new Block(machine()));

    public static final RegistryObject<Block> ENGINE = BLOCKS.register("engine",
            () -> new Block(machine()));

    public static final RegistryObject<Block> BALLAST_TANK = BLOCKS.register("ballast_tank",
            () -> new Block(machine()));

    public static final RegistryObject<Block> PUMP = BLOCKS.register("pump",
            () -> new Block(machine()));

    /** 声纳：带 3D 全息投影的方块实体。 */
    public static final RegistryObject<SonarBlock> SONAR = BLOCKS.register("sonar",
            () -> new SonarBlock(machine()));

    public static final RegistryObject<Block> HELM = BLOCKS.register("helm",
            () -> new Block(machine()));

    /** 船核心：扫描船体、划分舱室。 */
    public static final RegistryObject<ShipCoreBlock> SHIP_CORE = BLOCKS.register("ship_core",
            () -> new ShipCoreBlock(machine()));

    /** 站点装甲：前哨站专用，**不算船体**，避免把空间站识别成船。 */
    public static final RegistryObject<Block> STATION_PLATING = BLOCKS.register("station_plating",
            () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(40.0F, 2400.0F)
                    .sound(SoundType.NETHERITE_BLOCK)
                    .requiresCorrectToolForDrops()));

    private ModShipBlocks() {
    }

    /** 机器类型，用于船体扫描统计。 */
    public enum Machine {
        NONE, REACTOR, OXYGEN, ENGINE, PUMP, BALLAST, AIRLOCK, SONAR, HELM
    }

    public static Machine machineOf(BlockState state) {
        Block block = state.getBlock();
        if (block == NUCLEAR_REACTOR.get()) {
            return Machine.REACTOR;
        } else if (block == OXYGEN_GENERATOR.get()) {
            return Machine.OXYGEN;
        } else if (block == ENGINE.get()) {
            return Machine.ENGINE;
        } else if (block == PUMP.get()) {
            return Machine.PUMP;
        } else if (block == BALLAST_TANK.get()) {
            return Machine.BALLAST;
        } else if (block == AIRLOCK_DOOR.get()) {
            return Machine.AIRLOCK;
        } else if (block == SONAR.get()) {
            return Machine.SONAR;
        } else if (block == HELM.get()) {
            return Machine.HELM;
        }
        return Machine.NONE;
    }

    /** 对"分舱"而言是否算墙：开着的舱门不算墙（舱室连通）。 */
    public static boolean isSolidForShip(BlockState state) {
        if (state.getBlock() instanceof ShipDoorBlock door && state.getValue(ShipDoorBlock.OPEN)) {
            return false;
        }
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    /** 判定一个方块是否属于"船体结构"（用于识别船的范围）。核心不算，站体也不算。 */
    public static boolean isShipBlock(BlockState state) {
        Block block = state.getBlock();
        return block == HULL_GLASS.get()
                || block == HATCH.get()
                || block == AIRLOCK_DOOR.get()
                || block == NUCLEAR_REACTOR.get()
                || block == OXYGEN_GENERATOR.get()
                || block == ENGINE.get()
                || block == BALLAST_TANK.get()
                || block == PUMP.get()
                || block == SONAR.get()
                || block == HELM.get()
                || block == ModBlocks.SUBMARINE_PLATING.get();
    }

    private static BlockBehaviour.Properties metal() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_GRAY)
                .strength(20.0F, 600.0F)
                .sound(SoundType.METAL)
                .requiresCorrectToolForDrops();
    }

    private static BlockBehaviour.Properties machine() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_GRAY)
                .strength(10.0F, 300.0F)
                .sound(SoundType.METAL)
                .requiresCorrectToolForDrops()
                .noOcclusion();
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}
