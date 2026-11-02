package com.opamb.barotrauma.ship;

import com.opamb.barotrauma.network.ShipWaterStatePacket;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShipWaterStateBuilder} 的 L1 单测：水面高度必须按"逐层实际格数"算。
 *
 * <p>这是 2026-11-02 的独立修复补丁：旧实现用包围盒底面积
 * {@code (maxX-minX+1)*(maxZ-minZ+1)}，非矩形舱室会算错水位。下面的 L 形舱
 * 底面积 4 格、实际每层只有 3/1 格，两种算法结果明显不同。</p>
 */
class ShipWaterStateBuilderTest {

    /** L 形：y=0 有 3 格，y=1 有 1 格；包围盒是 2×2×2。 */
    private static ShipGeometry.Compartment lShape() {
        Set<BlockPos> cells = Set.of(
                new BlockPos(0, 0, 0),
                new BlockPos(1, 0, 0),
                new BlockPos(0, 0, 1),
                new BlockPos(0, 1, 0)
        );
        return new ShipGeometry.Compartment(cells, 0);
    }

    /** 4 格全在同一层，矩形。 */
    private static ShipGeometry.Compartment flatRectangle() {
        Set<BlockPos> cells = Set.of(
                new BlockPos(0, 0, 0),
                new BlockPos(1, 0, 0),
                new BlockPos(0, 0, 1),
                new BlockPos(1, 0, 1)
        );
        return new ShipGeometry.Compartment(cells, 0);
    }

    private static ShipGeometry.Result result(ShipGeometry.Compartment compartment, boolean pocket) {
        ShipGeometry.Bounds bounds = new ShipGeometry.Bounds(0, 0, 0, 1, 1, 1);
        return new ShipGeometry.Result(
                pocket ? List.of() : List.of(compartment),
                pocket ? List.of(compartment) : List.of(),
                0, compartment.volume(), bounds, Set.of());
    }

    private static float surfaceOf(ShipState state) {
        ShipWaterStatePacket packet = ShipWaterStateBuilder.build(state, 1L, false, 0.0F, 0.0F, 0.0F);
        assertEquals(7, packet.boxes().length, "一个舱室应该正好 7 个 float");
        return packet.boxes()[6];
    }

    @Test
    @DisplayName("0 水量 -> 水面在舱底 minY")
    void zeroWater_surfaceAtBottom() {
        ShipState state = new ShipState(result(lShape(), false), MachineCount.EMPTY);
        assertEquals(0.0F, surfaceOf(state), 1.0E-4F);
    }

    @Test
    @DisplayName("部分水（1.5 格）按底层 3 格算 -> 0.5，而不是 bbox 面积的 0.375")
    void partialWater_usesPerLayerCellCount() {
        ShipState state = new ShipState(result(lShape(), false), MachineCount.EMPTY);
        state.simulation().flood(0, 1.5D * ShipSimulation.UNITS_PER_BLOCK);

        float surface = surfaceOf(state);

        assertEquals(0.5F, surface, 1.0E-4F,
                "底层 3 格铺 1.5 格水 = 半层；用 bbox 底面积 4 会错误地得到 0.375");
    }

    @Test
    @DisplayName("部分水（3.5 格）铺满底层 + 顶层一半 -> 1.5")
    void partialWater_crossesLayerBoundary() {
        ShipState state = new ShipState(result(lShape(), false), MachineCount.EMPTY);
        state.simulation().flood(0, 3.5D * ShipSimulation.UNITS_PER_BLOCK);

        assertEquals(1.5F, surfaceOf(state), 1.0E-4F,
                "3 格底层 + 顶层 1 格的一半；bbox 算法会得到 0.875");
    }

    @Test
    @DisplayName("满水 -> 水面在 maxY + 1")
    void fullWater_surfaceAtTop() {
        ShipState state = new ShipState(result(lShape(), false), MachineCount.EMPTY);
        state.simulation().flood(0, 4.0D * ShipSimulation.UNITS_PER_BLOCK);

        assertEquals(2.0F, surfaceOf(state), 1.0E-4F);
    }

    @Test
    @DisplayName("超量水被 clamp 到 maxY + 1")
    void overfilledWater_clamped() {
        ShipState state = new ShipState(result(lShape(), false), MachineCount.EMPTY);
        state.simulation().flood(0, 99.0D * ShipSimulation.UNITS_PER_BLOCK);

        assertEquals(2.0F, surfaceOf(state), 1.0E-4F);
    }

    @Test
    @DisplayName("矩形舱室结果和旧 bbox 公式一致（回归）")
    void rectangularCompartment_matchesAreaFormula() {
        ShipState state = new ShipState(result(flatRectangle(), false), MachineCount.EMPTY);
        state.simulation().flood(0, 2.0D * ShipSimulation.UNITS_PER_BLOCK);

        assertEquals(0.5F, surfaceOf(state), 1.0E-4F);
    }

    @Test
    @DisplayName("连海空腔走 pocketWater 分支，同样按逐层格数算")
    void openPocket_usesPocketWaterAndPerLayerFill() {
        ShipState state = new ShipState(result(lShape(), true), MachineCount.EMPTY);
        // 0.1 秒 × 20 格³/秒 × 深度系数 1.0 = 2 格 = 200 单位。
        state.simulation().tickSeaFlood(0.1D, 0.0D);

        float surface = surfaceOf(state);
        assertTrue(surface > 0.0F && surface < 2.0F, "部分进水应该产生中间水面");
        // 2 格水：底层 3 格先铺，2/3 层 -> 0.6667。
        assertEquals(2.0F / 3.0F, surface, 1.0E-4F);
    }
}
