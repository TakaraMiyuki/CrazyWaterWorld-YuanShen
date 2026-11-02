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
 * {@link ShipGeometry} 的纯逻辑单测（L1）。
 *
 * <p>这个类的注释里写着"纯逻辑，不依赖 Level，方便以后单测"——这里就把那句话兑现了。
 * 用内存里的一堆 {@link BlockPos} 当船体，不需要开游戏。</p>
 *
 * <p>为什么值得测：分舱/密闭判定是「进水」「破口」「浮力」三条链路的根。
 * 我们这轮踩过的坑（`collectHull` 选中核心自己、装船只收到 1 格）就发生在这一层。</p>
 */
class ShipGeometryTest {

    /** 一个 5×5×5 的空心立方壳，中心在 (0,0,0)，内部是 3×3×3 空气。 */
    private static Set<BlockPos> hollowCube() {
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

    private static ShipGeometry.Probe probe(Set<BlockPos> solid) {
        return new ShipGeometry.Probe() {
            @Override
            public boolean isSolid(BlockPos pos) {
                return solid.contains(pos);
            }

            @Override
            public boolean isWater(BlockPos pos) {
                return false;
            }
        };
    }

    private static ShipGeometry.Result scan(Set<BlockPos> solid, BlockPos core, int radius) {
        Predicate<BlockPos> isShipBlock = solid::contains;
        return ShipGeometry.scanShip(core, radius, isShipBlock, probe(solid));
    }

    @Test
    @DisplayName("空心立方体 + 内部核心 -> 1 个舱室、98 格船体、密闭")
    void hollowCube_oneSealedCompartment() {
        ShipGeometry.Result r = scan(hollowCube(), BlockPos.ZERO, 4);

        assertEquals(98, r.hullBlocks(), "5^3 - 3^3 = 98 格壳");
        assertEquals(1, r.compartments().size(), "内部应该是一个舱室");
        assertEquals(27, r.totalVolume(), "3^3 = 27 格容积");
        assertTrue(r.sealed(), "没有破洞就是密闭");
        assertEquals(0, r.openPockets().size());
    }

    @Test
    @DisplayName("壳上挖一格 -> 变成连海空腔，不再密闭")
    void breachedHull_isOpenToSea() {
        Set<BlockPos> solid = hollowCube();
        solid.remove(new BlockPos(0, 2, 0));   // 顶面打掉一格

        ShipGeometry.Result r = scan(solid, BlockPos.ZERO, 4);

        assertFalse(r.sealed(), "有破口就不该判成密闭");
        assertEquals(1, r.openPockets().size(), "那个舱室应该变成连海空腔");
        assertEquals(0, r.compartments().size(), "不该再有任何封闭舱室");
    }

    @Test
    @DisplayName("核心自己不算船体方块（否则装船只收到 1 格）")
    void coreItselfIsNotCollected() {
        // 核心放在壳外：如果 collectHull 把核心自己算进去，会选到距离 0 的核心
        Set<BlockPos> solid = hollowCube();
        solid.add(BlockPos.ZERO);   // 核心位置也标成"船体方块"

        ShipGeometry.Result r = scan(solid, BlockPos.ZERO, 4);

        assertEquals(98, r.hullBlocks(),
                "核心必须被排除，选中的应该是外面那层壳，而不是它自己");
        assertFalse(r.hull().contains(BlockPos.ZERO), "hull 集合里不该有核心");
    }

    @Test
    @DisplayName("两块互不相连的船体 -> 只取离核心最近的那一块")
    void disconnectedComponents_picksNearestToCore() {
        Set<BlockPos> solid = new HashSet<>();
        // 近处：1 格的小方块
        solid.add(new BlockPos(1, 0, 0));
        // 远处：很大的一坨，但离核心远
        for (int x = 10; x < 16; x++) {
            for (int y = 0; y < 3; y++) {
                for (int z = 0; z < 3; z++) {
                    solid.add(new BlockPos(x, y, z));
                }
            }
        }

        ShipGeometry.Result r = scan(solid, BlockPos.ZERO, 20);

        assertEquals(1, r.hullBlocks(), "应该选离核心最近的那一块（1 格）");
    }

    @Test
    @DisplayName("核心附近没有船体方块 -> 返回空结果，包围盒收缩到核心一格")
    void emptyShip_returnsEmptyResult() {
        ShipGeometry.Result r = scan(Set.of(), BlockPos.ZERO, 8);

        assertEquals(0, r.hullBlocks());
        assertTrue(r.hull().isEmpty());
        assertEquals(1, r.bounds().sizeX(), "空船包围盒退化成一格");
        assertEquals(0, r.compartments().size());
    }

    @Test
    @DisplayName("shifted 平移后舱室/容积/密闭性完全一致，只有坐标变了")
    void shifted_keepsShape() {
        ShipGeometry.Result original = scan(hollowCube(), BlockPos.ZERO, 4);
        ShipGeometry.Result moved = original.shifted(100, -50, 7);

        assertEquals(original.compartments().size(), moved.compartments().size());
        assertEquals(original.totalVolume(), moved.totalVolume());
        assertEquals(original.hullBlocks(), moved.hullBlocks());
        assertEquals(original.sealed(), moved.sealed());
        assertEquals(original.bounds().minX() + 100, moved.bounds().minX());
        assertEquals(original.bounds().minY() - 50, moved.bounds().minY());
        assertEquals(original.bounds().minZ() + 7, moved.bounds().minZ());
        assertTrue(moved.hull().contains(new BlockPos(100 - 2, -50 - 2, 7 - 2)),
                "hull 坐标也要跟着平移");
    }
}
