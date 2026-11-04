package com.opamb.barotrauma.ship;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 排水泵（L1 纯逻辑）：按速率抽水、不抽到负数、能抽连海空腔的积水。 */
class ShipDrainPumpTest {

    /** 5×5×5 封闭空心船壳（无破口）。 */
    private static Set<BlockPos> sealedCube() {
        Set<BlockPos> solid = new HashSet<>();
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    boolean shell = Math.abs(x) == 2 || Math.abs(y) == 2 || Math.abs(z) == 2;
                    if (shell) {
                        solid.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return solid;
    }

    /** 5×5×5 空心船壳，(2,0,0) 破一格 → 一个连海空腔。 */
    private static Set<BlockPos> breachedCube() {
        Set<BlockPos> solid = sealedCube();
        solid.remove(new BlockPos(2, 0, 0));
        return solid;
    }

    private static ShipSimulation simulation(Set<BlockPos> solid) {
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
        ShipGeometry.Result geometry = ShipGeometry.scanShip(BlockPos.ZERO, 8, isShipBlock, probe);
        return new ShipSimulation(geometry, new MachineCount(0, 0, 0, 0, 0, 0, 1));
    }

    @Test
    void 按速率抽密闭舱室的水() {
        ShipSimulation sim = simulation(sealedCube());
        // 水泵只抽自己所在的那个水体（见 ShipSimulation.pumpBody），必须给泵格。
        sim.setDrainPumpCell(new BlockPos(0, 0, 0));
        sim.flood(0, 1000.0D);
        assertEquals(1000.0D, sim.waterUnits(), 1.0E-6);

        sim.setDrainPump(true, 600.0D);
        sim.tick(1.0D); // 秒

        // 600 单位/秒 × 1 秒 = 6 格；剩余 400
        assertEquals(400.0D, sim.waterUnits(), 1.0E-6);
    }

    @Test
    void 关闭时不排水() {
        ShipSimulation sim = simulation(sealedCube());
        sim.flood(0, 1000.0D);
        sim.setDrainPump(false, 600.0D);
        sim.tick(1.0D);
        assertEquals(1000.0D, sim.waterUnits(), 1.0E-6);
    }

    @Test
    void 不会抽成负数且能抽连海空腔() {
        ShipSimulation sim = simulation(breachedCube());
        sim.setDrainPumpCell(new BlockPos(0, 0, 0));
        // 破口空腔里先灌 500 单位（flood 只作用于密闭舱室，连海空腔要用 seedWater）
        assertEquals(1, sim.geometry().openPockets().size());
        sim.seedWater(0.0D, 500.0D);
        double before = sim.pocketWaterTotal() + sim.waterUnits();
        assertTrue(before > 0.0D);

        sim.setDrainPump(true, 3000.0D);
        for (int i = 0; i < 10; i++) {
            sim.tick(1.0D);
        }
        assertEquals(0.0D, sim.pocketWaterTotal() + sim.waterUnits(), 1.0E-6);
    }

    @Test
    void 速率被夹紧到合法范围() {
        ShipGeometry.Probe emptyProbe = new ShipGeometry.Probe() {
            @Override
            public boolean isSolid(BlockPos pos) {
                return false;
            }

            @Override
            public boolean isWater(BlockPos pos) {
                return false;
            }
        };
        ShipSimulation sim = new ShipSimulation(
                ShipGeometry.scanShip(BlockPos.ZERO, 8, p -> false, emptyProbe),
                new MachineCount(0, 0, 0, 0, 0, 0, 0));
        sim.setDrainPump(true, 1.0D);
        assertEquals(100.0D, sim.drainPumpRate(), 1.0E-6);
        sim.setDrainPump(true, 99999.0D);
        assertEquals(3000.0D, sim.drainPumpRate(), 1.0E-6);
    }
}
