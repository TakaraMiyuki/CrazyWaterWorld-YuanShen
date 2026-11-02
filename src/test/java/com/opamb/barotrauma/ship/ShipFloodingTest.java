package com.opamb.barotrauma.ship;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 破口进水（L1 纯逻辑）：船壳破一格 → 连海空腔的水量应随时间长。
 *
 * <p>对应《潜渊症》的 {@code Gap.UpdateRoomToOut}：水量是渐进累加的，不是瞬间灌满。</p>
 */
class ShipFloodingTest {

    /** 5×5×5 空心壳，但 (2,0,0) 那一格破掉，变成一个连海空腔。 */
    private static Set<BlockPos> breachedCube() {
        Set<BlockPos> solid = new HashSet<>();
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    boolean shell = Math.abs(x) == 2 || Math.abs(y) == 2 || Math.abs(z) == 2;
                    if (shell && !(x == 2 && y == 0 && z == 0)) {
                        solid.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return solid;
    }

    private static ShipGeometry.Result scan(Set<BlockPos> solid) {
        ShipGeometry.Probe probe = new ShipGeometry.Probe() {
            @Override
            public boolean isSolid(BlockPos pos) {
                return solid.contains(pos);
            }

            @Override
            public boolean isWater(BlockPos pos) {
                return false;
            }
        };
        Predicate<BlockPos> isShipBlock = solid::contains;
        return ShipGeometry.scanShip(new BlockPos(0, 0, 0), 12, isShipBlock, probe);
    }

    @Test
    @DisplayName("破口空腔的水量随时间增加，并被容积封顶")
    void breachFloodsGradually() {
        ShipGeometry.Result geometry = scan(breachedCube());
        assertFalse(geometry.openPockets().isEmpty(), "破了一格应该出现连海空腔");

        ShipSimulation sim = new ShipSimulation(geometry, MachineCount.EMPTY);
        assertEquals(0.0D, sim.pocketWater(0), 1.0E-9D, "刚破口时不该有水");

        // 用 0.1 秒的小步长：按当前进水速率（20 格³/秒 × 深度系数），
        // 27 格空腔约 1 秒灌满；步长太大会一步到顶，"继续增加"就测不出来了。
        sim.tickSeaFlood(0.1D, 50.0D);
        double first = sim.pocketWater(0);
        assertTrue(first > 0.0D, "0.1 秒后应该有水进来");

        sim.tickSeaFlood(0.1D, 50.0D);
        assertTrue(sim.pocketWater(0) > first, "水量应该继续增加");

        double capacity = geometry.openPockets().get(0).volume() * ShipSimulation.UNITS_PER_BLOCK;
        sim.tickSeaFlood(10000.0D, 50.0D);
        assertEquals(capacity, sim.pocketWater(0), 1.0E-6D, "最终不能超过空腔容积");
    }

    @Test
    @DisplayName("连海空腔的水也要算进进水比例，否则破船不会下沉")
    void floodRatioIncludesOpenPockets() {
        ShipGeometry.Result geometry = scan(breachedCube());
        ShipState state = new ShipState(geometry, MachineCount.EMPTY);
        assertEquals(0.0D, state.floodRatio(), 1.0E-9D, "刚破口时是干的");
        state.simulation().tickSeaFlood(0.1D, 50.0D);
        assertTrue(state.floodRatio() > 0.0D, "连海空腔进水后进水比例应该大于 0");
    }
}
