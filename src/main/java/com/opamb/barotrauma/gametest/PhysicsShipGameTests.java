package com.opamb.barotrauma.gametest;

import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.blockentity.ShipCoreBlockEntity;
import com.opamb.barotrauma.registry.ModShipBlocks;
import com.opamb.barotrauma.ship.ShipState;
import com.opamb.barotrauma.ship.vs.VsPilot;
import com.opamb.barotrauma.ship.vs.VsSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Set;

/**
 * 物理船（VS2）的游戏内测试（L2）。
 *
 * <p>为什么必须有这一层：`runServer` 的无头自检只能证明"代码不崩、数值算得对"，
 * 证明不了"方块真的进了刚体"。我们这轮踩的坑（只收进声纳一格、质量从 769600
 * 变成 300）在自检里完全看不出来——它只能靠 {@code /barotrauma ship vs status}
 * 人工看日志发现。这里把它变成断言。</p>
 *
 * <p>用 Forge 的 GameTest：`gradlew runGameTestServer`，退出码 = 失败的必需测试数。
 * 结构模板是全空气的 {@code empty_16x8x16}，由 {@code tools/gen_gametest_structure.py}
 * 生成；船体方块在测试里用代码摆，不依赖任何存档。</p>
 *
 * <p>注意：这个世界的物理行为和玩家的深海维度不完全一样，所以这里<strong>不</strong>
 * 试图复现"某个坐标装配失败"那个具体 bug（那是位置相关的，只能靠真实世界扫坐标）。
 * 这里守的是"装配 → 质量 → 拆回"这条链路的正确性。</p>
 */
@GameTestHolder(BarotraumaMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhysicsShipGameTests {

    /** 5×5×5 空心壳 = 98 格；透明试验船壳的 vs_mass 是 2000，声纳 300。 */
    private static final int HULL_BLOCKS = 98;
    private static final double HULL_MASS = 2000.0D;
    private static final double SONAR_MASS = 300.0D;
    private static final double EXPECTED_MASS = HULL_BLOCKS * HULL_MASS + SONAR_MASS;

    /** 船体在模板里的相对起点（模板 16×8×16，留出边距）。 */
    private static final int BASE_X = 5;
    private static final int BASE_Y = 1;
    private static final int BASE_Z = 5;
    /** 声纳悬在舱室正中间，**故意不和船壳相连**——这正是"装船只收到 1 格"那个 bug 的现场。 */
    private static final int SONAR_X = BASE_X + 2;
    private static final int SONAR_Y = BASE_Y + 2;
    private static final int SONAR_Z = BASE_Z + 2;

    private PhysicsShipGameTests() {
    }

    /** 上一个测试没拆干净的话先拆掉，VsPilot 是单例、同时只允许一艘物理船。 */
    private static void resetShip(GameTestHelper helper) {
        if (VsSupport.available() && VsPilot.hasShip()) {
            try {
                VsPilot.dock(helper.getLevel());
            } catch (Throwable ignored) {
                // 拆不掉也要继续，否则后面的断言会给出误导性的失败
            }
        }
    }

    /** 在模板里摆一艘 5×5×5 的空心船 + 舱中间一台悬空的声纳。 */
    private static void buildTestShip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockState hull = ModShipBlocks.TEST_HULL.get().defaultBlockState();

        for (int dx = 0; dx < 5; dx++) {
            for (int dy = 0; dy < 5; dy++) {
                for (int dz = 0; dz < 5; dz++) {
                    boolean shell = dx == 0 || dx == 4 || dy == 0 || dy == 4 || dz == 0 || dz == 4;
                    if (!shell) {
                        continue;
                    }
                    level.setBlock(abs(helper, BASE_X + dx, BASE_Y + dy, BASE_Z + dz), hull, 3);
                }
            }
        }
        level.setBlock(abs(helper, SONAR_X, SONAR_Y, SONAR_Z),
                ModShipBlocks.SONAR.get().defaultBlockState(), 3);
    }

    private static BlockPos abs(GameTestHelper helper, int dx, int dy, int dz) {
        return helper.absolutePos(new BlockPos(dx, dy, dz));
    }

    private static ShipState coreState(GameTestHelper helper) {
        BlockPos sonarAbs = abs(helper, SONAR_X, SONAR_Y, SONAR_Z);
        if (!(helper.getLevel().getBlockEntity(sonarAbs) instanceof ShipCoreBlockEntity core)) {
            helper.fail("声纳方块实体没建出来：" + sonarAbs);
            throw new IllegalStateException("unreachable");
        }
        return core.rescan(helper.getLevel());
    }

    // ------------------------------------------------------------------ 测试

    /**
     * 回归测试：「核心自己算船体 → 装船只收到 1 格」。
     *
     * <p>声纳悬在舱室中间，和船壳不连通。{@code collectHull} 要选的是<strong>离核心最近的
     * 另一块</strong>（船壳 98 格），不能选核心自己（1 格）。</p>
     */
    @GameTest(template = "empty_16x8x16", templateNamespace = BarotraumaMod.MOD_ID, batch = "physics_scan")
    public static void scan_collectsWholeHullNotJustCore(GameTestHelper helper) {
        resetShip(helper);
        buildTestShip(helper);

        ShipState state = coreState(helper);

        helper.assertTrue(state.geometry().hullBlocks() == HULL_BLOCKS,
                "船体方块应该收满 " + HULL_BLOCKS + " 格，实际 " + state.geometry().hullBlocks()
                        + "（=1 说明又选中核心自己了）");
        helper.assertTrue(state.geometry().sealed(), "密封的立方壳应该判成密闭");
        helper.assertTrue(state.geometry().compartments().size() == 1, "应该只有 1 个舱室");

        Set<BlockPos> all = state.collectBlocks(helper.getLevel());
        helper.assertTrue(all.size() == HULL_BLOCKS + 1,
                "装船集合应该是 98 格船壳 + 1 台声纳 = 99，实际 " + all.size());
        helper.succeed();
    }

    /**
     * 核心断言：装配之后，<strong>所有方块都要进刚体</strong>。
     *
     * <p>质量对不上就是「船和声纳不是一体 / 水灌进来」那类现象的根：
     * 方块被从世界里删掉、却没有进 VS2 的船。</p>
     */
    @GameTest(template = "empty_16x8x16", templateNamespace = BarotraumaMod.MOD_ID, batch = "physics_launch")
    public static void launch_putsEveryBlockIntoRigidBody(GameTestHelper helper) {
        resetShip(helper);
        if (!VsSupport.available()) {
            helper.fail("没装 VS2，物理船测试跑不了");
            return;
        }
        buildTestShip(helper);

        ShipState state = coreState(helper);
        BlockPos sonarAbs = abs(helper, SONAR_X, SONAR_Y, SONAR_Z);
        Set<BlockPos> blocks = state.collectBlocks(helper.getLevel());

        String message = VsPilot.launch(helper.getLevel(), blocks, state, sonarAbs);
        helper.assertTrue(VsPilot.hasShip(), "装配应该成功，实际返回：" + message);

        double mass = VsPilot.shipMass();
        // VS2 算质量会取整到 kg，允许 ±1 的浮点误差
        helper.assertTrue(Math.abs(mass - EXPECTED_MASS) <= 1.0D,
                "刚体质量应该是 " + EXPECTED_MASS + "（98×2000 + 300），实际 " + mass
                        + "；差得多说明方块没全进刚体。launch 返回：" + message);

        var pos = VsPilot.shipPosition();
        helper.assertTrue(pos != null, "刚体应该有世界坐标");
        BlockPos expectCenter = abs(helper, BASE_X + 2, BASE_Y + 2, BASE_Z + 2);
        helper.assertTrue(Math.abs(pos.x - (expectCenter.getX() + 0.5D)) <= 1.5D,
                "刚体 X 应该落在船的位置附近，实际 " + pos.x + "，期望约 " + (expectCenter.getX() + 0.5D));

        resetShip(helper);
        helper.succeed();
    }

    /**
     * 拆船必须把方块原样铺回世界（VS2 没有"刚体还原方块"的 API，我们靠装配快照）。
     */
    @GameTest(template = "empty_16x8x16", templateNamespace = BarotraumaMod.MOD_ID, batch = "physics_dock")
    public static void dock_restoresBlocksToWorld(GameTestHelper helper) {
        resetShip(helper);
        if (!VsSupport.available()) {
            helper.fail("没装 VS2，物理船测试跑不了");
            return;
        }
        buildTestShip(helper);

        ShipState state = coreState(helper);
        BlockPos sonarAbs = abs(helper, SONAR_X, SONAR_Y, SONAR_Z);
        Set<BlockPos> blocks = state.collectBlocks(helper.getLevel());
        VsPilot.launch(helper.getLevel(), blocks, state, sonarAbs);
        helper.assertTrue(VsPilot.hasShip(), "前提：装配成功");

        String dockMessage = VsPilot.dock(helper.getLevel());
        helper.assertTrue(!VsPilot.hasShip(), "拆完之后不该还有物理船");

        // 不断言"具体某一格"，而是数整个船体区域：拆船要么全铺回来，要么有明显缺口。
        // 位置精确断言在 VS2 刚体上不稳（它自己会算质心/位移），数方块才是我们要守的东西。
        ServerLevel level = helper.getLevel();
        int hull = 0;
        int sonar = 0;
        for (int dx = 0; dx < 5; dx++) {
            for (int dy = 0; dy < 5; dy++) {
                for (int dz = 0; dz < 5; dz++) {
                    var at = level.getBlockState(abs(helper, BASE_X + dx, BASE_Y + dy, BASE_Z + dz));
                    if (at.is(ModShipBlocks.TEST_HULL.get())) {
                        hull++;
                    } else if (at.is(ModShipBlocks.SONAR.get())) {
                        sonar++;
                    }
                }
            }
        }
        helper.assertTrue(sonar == 1,
                "拆回来之后船里应该有 1 台声纳，实际 " + sonar + "。dock 返回：" + dockMessage);
        helper.assertTrue(hull == HULL_BLOCKS,
                "拆回来之后应该有 " + HULL_BLOCKS + " 格船壳，实际 " + hull + "。dock 返回：" + dockMessage);

        helper.succeed();
    }
}
