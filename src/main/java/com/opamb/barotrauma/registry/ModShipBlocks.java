package com.opamb.barotrauma.registry;

import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.block.HullBlock;
import com.opamb.barotrauma.block.SonarBlock;
import com.opamb.barotrauma.block.SonarProxyBlock;
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
 * 潜艇方块注册表：船体 + 声纳台（船脑）。
 *
 * <p>2026-11-02 按用户要求清理：五台机器（反应堆 / 制氧机 / 引擎 / 压载舱 / 水泵）
 * 和舱门 / 舱口全部删除——它们除了"被模拟计数"之外没有任何实现，放在船上纯属装饰。
 * 现在船上只有船壳、舷窗和声纳台；开船靠 VS2 物理刚体。</p>
 */
public final class ModShipBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, BarotraumaMod.MOD_ID);

    // ---- 船体 ----
    public static final RegistryObject<Block> HULL_GLASS = BLOCKS.register("hull_glass",
            () -> new HullBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_BLUE)
                    .strength(0.3F)
                    .sound(SoundType.GLASS)
                    .noOcclusion()
                    .isViewBlocking((state, level, pos) -> !HullBlock.isBreached(state))
                    .isSuffocating((state, level, pos) -> !HullBlock.isBreached(state))));

    /**
     * 测试用透明船壳（调试专用）。
     *
     * <p>改物理船的时候看不见船里面，只能靠 `/barotrauma ship vs status` 猜。
     * 这块方块就是"玻璃装甲板"：和装甲板一样算船体、能被 VS2 收进刚体，
     * 但渲染成全透明，站在船里也能直接看到船壳和方块边界。
     * 初始潜艇的船壳用它造，方便肉眼验证"船和声纳是不是一体的"。</p>
     */
    public static final RegistryObject<Block> TEST_HULL = BLOCKS.register("test_hull",
            () -> new HullBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_BLUE)
                    .strength(2.0F)
                    .sound(SoundType.GLASS)
                    .noOcclusion()
                    .isViewBlocking((state, level, pos) -> false)
                    .isSuffocating((state, level, pos) -> false)));

    /** 声纳：带 3D 全息投影的方块实体。**同时兼任船核心**（见 SonarBlockEntity）。 */
    public static final RegistryObject<SonarBlock> SONAR = BLOCKS.register("sonar",
            () -> new SonarBlock(machine()));

    /**
     * 物理船声纳的交互锚点（不可见 / 不碰撞 / 不可破坏）。
     *
     * <p>VS2 2.4.11 船上不能右键方块，而声纳又必须能点开。装配后把这块方块放在
     * 声纳原来的位置（跟着船整格移动），右键它直接打开声纳界面。不属于船体方块，
     * 不会被船体扫描或二次装配收走。</p>
     */
    public static final RegistryObject<Block> SONAR_PROXY = BLOCKS.register("sonar_proxy",
            () -> new SonarProxyBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.NONE)
                    .strength(-1.0F, 3600000.0F)
                    .noLootTable()
                    .noOcclusion()
                    .noCollission()
                    .isViewBlocking((state, level, pos) -> false)
                    .isSuffocating((state, level, pos) -> false)));

    public static final RegistryObject<Block> STATION_PLATING = BLOCKS.register("station_plating",
            () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(40.0F, 2400.0F)
                    .sound(SoundType.NETHERITE_BLOCK)
                    .requiresCorrectToolForDrops()));

    private ModShipBlocks() {
    }

    /** 机器类型，用于船体扫描统计（现在只剩声纳）。 */
    public enum Machine {
        NONE, SONAR
    }

    public static Machine machineOf(BlockState state) {
        Block block = state.getBlock();
        if (block == SONAR.get()) {
            return Machine.SONAR;
        }
        return Machine.NONE;
    }

    /** 对"分舱"而言是否算墙：breached 的船壳是洞，必须让扫描把它当空气。 */
    public static boolean isSolidForShip(BlockState state) {
        if (state.getBlock() instanceof HullBlock && HullBlock.isBreached(state)) {
            return false;
        }
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    /** 判定一个方块是否属于"船体结构"（用于识别船的范围）。核心不算，站体也不算。 */
    public static boolean isShipBlock(BlockState state) {
        Block block = state.getBlock();
        return block == HULL_GLASS.get()
                || block == TEST_HULL.get()
                || block == SONAR.get()
                || block == ModBlocks.SUBMARINE_PLATING.get();
    }

    /**
     * 可受损船壳的基础最大血量（原版：外壳 200 / 舷窗 100）。
     *
     * <p>声纳是船脑，不参与船壳耐久；内墙在本模组里没有单独方块，暂时和外壳同值。
     * T1/T2/T3 本身不改变这个值，只限制“船体加固”升级等级（每级 +20%）。</p>
     */
    public static float maxHealth(BlockState state) {
        if (state.is(HULL_GLASS.get())) {
            return com.opamb.barotrauma.ship.HullDamage.GLASS_MAX_HEALTH;
        }
        if (state.is(TEST_HULL.get())) {
            return com.opamb.barotrauma.ship.HullDamage.TEST_HULL_MAX_HEALTH;
        }
        if (state.is(ModBlocks.SUBMARINE_PLATING.get())) {
            return com.opamb.barotrauma.ship.HullDamage.PLATING_MAX_HEALTH;
        }
        return 0.0F;
    }

    /** 能不能被砸、被焊：声纳和普通方块都不行。 */
    public static boolean isRepairableHull(BlockState state) {
        return maxHealth(state) > 0.0F;
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
