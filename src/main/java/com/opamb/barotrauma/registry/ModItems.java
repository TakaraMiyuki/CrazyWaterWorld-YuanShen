package com.opamb.barotrauma.registry;

import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.item.DivingKnifeItem;
import com.opamb.barotrauma.item.OxygenTankItem;
import com.opamb.barotrauma.item.WeldingToolItem;
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

    /** 焊接工具：右键持续修船壳，耐久就是燃料（原版燃料罐 200 点，这里放宽到 400）。 */
    public static final RegistryObject<Item> WELDING_TOOL = ITEMS.register("welding_tool",
            () -> new WeldingToolItem(new Item.Properties().stacksTo(1).durability(400)));

    /** 潜艇装甲板的方块物品。 */
    public static final RegistryObject<Item> SUBMARINE_PLATING =
            blockItem("submarine_plating", ModBlocks.SUBMARINE_PLATING);

    // ---- M3 潜艇方块 ----
    public static final RegistryObject<Item> HULL_GLASS = blockItem("hull_glass", ModShipBlocks.HULL_GLASS);
    /** 测试用透明船壳（调试专用）。 */
    public static final RegistryObject<Item> TEST_HULL = blockItem("test_hull", ModShipBlocks.TEST_HULL);
    public static final RegistryObject<Item> SONAR = blockItem("sonar", ModShipBlocks.SONAR);
    public static final RegistryObject<Item> STATION_PLATING = blockItem("station_plating", ModShipBlocks.STATION_PLATING);
    public static final RegistryObject<Item> HATCH = blockItem("hatch", ModShipBlocks.HATCH);
    public static final RegistryObject<Item> BULKHEAD_DOOR =
            blockItem("bulkhead_door", ModShipBlocks.BULKHEAD_DOOR);

    /** 排水泵方块物品。 */
    public static final RegistryObject<Item> DRAIN_PUMP = blockItem("drain_pump", ModBlocks.DRAIN_PUMP);

    // ---- 任务/经济 ----
    public static final RegistryObject<Item> MISSION_TERMINAL =
            blockItem("mission_terminal", ModBlocks.MISSION_TERMINAL);
    public static final RegistryObject<Item> BLACK_BOX_BLOCK_ITEM =
            blockItem("black_box_block", ModBlocks.BLACK_BOX_BLOCK);
    /** 黑匣子（物品）：打捞任务的交付物，沉船里的 black_box_block 掉落。 */
    public static final RegistryObject<Item> BLACK_BOX = ITEMS.register("black_box",
            () -> new Item(new Item.Properties()));
    /** 补给箱（物品）：DELIVERY 任务的货物，接任务时发，送到 B 中继站交付。 */
    public static final RegistryObject<Item> SUPPLY_CRATE = ITEMS.register("supply_crate",
            () -> new Item(new Item.Properties()));

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
