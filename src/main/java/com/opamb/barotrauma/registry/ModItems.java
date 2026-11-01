package com.opamb.barotrauma.registry;

import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.item.DivingKnifeItem;
import com.opamb.barotrauma.item.OxygenTankItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 物品注册表。
 */
public final class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, BarotraumaMod.MOD_ID);

    /** 氧气瓶：耐久即氧气量，右键补满氧气。 */
    public static final RegistryObject<Item> OXYGEN_TANK = ITEMS.register("oxygen_tank",
            () -> new OxygenTankItem(new Item.Properties().stacksTo(1).durability(300)));

    /** 潜水刀：铁剑档位的近战武器。 */
    public static final RegistryObject<Item> DIVING_KNIFE = ITEMS.register("diving_knife",
            () -> new DivingKnifeItem(Tiers.IRON, 2, -2.2F, new Item.Properties()));

    /** 潜艇装甲板的方块物品。 */
    public static final RegistryObject<Item> SUBMARINE_PLATING =
            blockItem("submarine_plating", ModBlocks.SUBMARINE_PLATING);

    // ---- M3 潜艇方块 ----
    public static final RegistryObject<Item> HULL_GLASS = blockItem("hull_glass", ModShipBlocks.HULL_GLASS);
    public static final RegistryObject<Item> HATCH = blockItem("hatch", ModShipBlocks.HATCH);
    public static final RegistryObject<Item> AIRLOCK_DOOR = blockItem("airlock_door", ModShipBlocks.AIRLOCK_DOOR);
    public static final RegistryObject<Item> NUCLEAR_REACTOR = blockItem("nuclear_reactor", ModShipBlocks.NUCLEAR_REACTOR);
    public static final RegistryObject<Item> OXYGEN_GENERATOR = blockItem("oxygen_generator", ModShipBlocks.OXYGEN_GENERATOR);
    public static final RegistryObject<Item> ENGINE = blockItem("engine", ModShipBlocks.ENGINE);
    public static final RegistryObject<Item> BALLAST_TANK = blockItem("ballast_tank", ModShipBlocks.BALLAST_TANK);
    public static final RegistryObject<Item> PUMP = blockItem("pump", ModShipBlocks.PUMP);
    public static final RegistryObject<Item> SONAR = blockItem("sonar", ModShipBlocks.SONAR);
    public static final RegistryObject<Item> HELM = blockItem("helm", ModShipBlocks.HELM);
    public static final RegistryObject<Item> SHIP_CORE = blockItem("ship_core", ModShipBlocks.SHIP_CORE);
    public static final RegistryObject<Item> STATION_PLATING = blockItem("station_plating", ModShipBlocks.STATION_PLATING);

    private ModItems() {
    }

    /** 为一个方块生成同名 BlockItem。 */
    private static RegistryObject<Item> blockItem(String name, RegistryObject<? extends Block> block) {
        return ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
