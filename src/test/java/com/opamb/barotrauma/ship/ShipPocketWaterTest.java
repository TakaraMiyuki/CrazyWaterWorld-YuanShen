package com.opamb.barotrauma.ship;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShipPocketWater} 的 L1 单测：按列模拟水位，水只能经过共享格子流动。
 *
 * <p>用户实测的“假物理”：隔墙顶部留孔，破口侧刚开始进水，另一侧同步涨水；
 * 多个隔断时水越过第一个就无视后面的。这里验证：
 * 孔以下时另一侧必须是干的；溢流后另一侧按自己的水位慢慢涨；底部孔两侧一起涨。</p>
 */
class ShipPocketWaterTest {

    /** 左舱 A：x=0..1，y=0..1，z=0；右舱 B：x=3..4，y=0..1，z=0。 */
    private static Set<BlockPos> twoRooms(BlockPos hole) {
        return Set.of(
                // A
                new BlockPos(0, 0, 0), new BlockPos(1, 0, 0),
                new BlockPos(0, 1, 0), new BlockPos(1, 1, 0),
                // B
                new BlockPos(3, 0, 0), new BlockPos(4, 0, 0),
                new BlockPos(3, 1, 0), new BlockPos(4, 1, 0),
                hole
        );
    }

    private static boolean wet(ShipPocketWater.Shape shape, BlockPos pos) {
        float level = shape.levelAt(pos.getX(), pos.getZ());
        return !Float.isNaN(level) && level > pos.getY() + 1.0E-3F;
    }

    @Test
    @DisplayName("顶部孔：水位没到孔时，另一侧不进水")
    void topHole_otherRoomStaysDryBelowHole() {
        BlockPos hole = new BlockPos(2, 1, 0);
        Set<BlockPos> cells = twoRooms(hole);
        // 1.9 格：A 下层两列各约 0.95 格水，远没到孔底。
        ShipPocketWater.Shape shape = ShipPocketWater.compute(cells,
                List.of(new BlockPos(0, 0, 0)), 190.0D);

        assertEquals(0.95F, shape.levelAt(1, 0), 0.03F, "破口侧两列应该基本齐平");
        assertFalse(wet(shape, new BlockPos(3, 0, 0)), "水面在孔以下时，另一侧底层必须干燥");
        assertFalse(wet(shape, new BlockPos(3, 1, 0)), "另一侧顶层也不能进水");
        assertFalse(wet(shape, hole), "孔本身在水面以上，不应含水");
    }

    @Test
    @DisplayName("顶部孔：溢流后另一侧按自己的水位慢慢涨，不是等两边齐平才出现")
    void topHole_spillRendersGradually() {
        BlockPos hole = new BlockPos(2, 1, 0);
        Set<BlockPos> cells = twoRooms(hole);
        // 3 格：A 两侧下层填满（各 1 格），剩 1 格从孔流到 B 两层各一半。
        ShipPocketWater.Shape shape = ShipPocketWater.compute(cells,
                List.of(new BlockPos(0, 0, 0)), 300.0D);

        assertEquals(1.0F, shape.levelAt(1, 0), 0.05F, "破口侧应该满到孔底");
        assertEquals(0.5F, shape.levelAt(3, 0), 0.08F,
                "另一侧应该是自己的水位（约半满），而不是瞬间和破口侧齐平");
        assertFalse(wet(shape, new BlockPos(0, 1, 0)), "溢流口以上还没水");
        assertFalse(wet(shape, new BlockPos(4, 1, 0)), "另一侧溢流口以上也没水");
    }

    @Test
    @DisplayName("顶部孔：水量足够填满两侧溢流口以下后，孔及以上才有水")
    void topHole_otherRoomFloodsAfterLevelReachesHole() {
        BlockPos hole = new BlockPos(2, 1, 0);
        Set<BlockPos> cells = twoRooms(hole);
        // 4.5 格：两侧下层全满（4 格），余下 0.5 格铺在 y=1 的 5 个格子上 -> 水位 1.1。
        ShipPocketWater.Shape shape = ShipPocketWater.compute(cells,
                List.of(new BlockPos(0, 0, 0)), 450.0D);

        assertEquals(1.1F, shape.levelAt(0, 0), 0.06F, "两侧应该一起涨到溢流口以上");
        assertTrue(wet(shape, hole), "水面进入孔所在层后，孔应该含水");
        assertTrue(wet(shape, new BlockPos(3, 0, 0)), "另一侧应该已经有水");
        assertTrue(wet(shape, new BlockPos(4, 1, 0)), "另一侧顶层也应被水面覆盖");
    }

    @Test
    @DisplayName("底部孔：两侧一开始就同时进水")
    void bottomHole_bothRoomsFloodTogether() {
        BlockPos hole = new BlockPos(2, 0, 0);
        Set<BlockPos> cells = twoRooms(hole);
        ShipPocketWater.Shape shape = ShipPocketWater.compute(cells,
                List.of(new BlockPos(0, 0, 0)), 150.0D);

        assertTrue(wet(shape, new BlockPos(1, 0, 0)));
        assertTrue(wet(shape, new BlockPos(3, 0, 0)), "底部连通时水会立刻流到另一侧");
    }

    @Test
    @DisplayName("多个顶部隔断：水依次传递，不会越过第一个就全部一起涨")
    void multipleTopHoles_floodInSequence() {
        // A(x0..1) - 孔1(x2,y1) - B(x3..4) - 孔2(x5,y1) - C(x6..7)，每间两层。
        Set<BlockPos> cells = Set.of(
                new BlockPos(0, 0, 0), new BlockPos(1, 0, 0),
                new BlockPos(0, 1, 0), new BlockPos(1, 1, 0),
                new BlockPos(2, 1, 0),
                new BlockPos(3, 0, 0), new BlockPos(4, 0, 0),
                new BlockPos(3, 1, 0), new BlockPos(4, 1, 0),
                new BlockPos(5, 1, 0),
                new BlockPos(6, 0, 0), new BlockPos(7, 0, 0),
                new BlockPos(6, 1, 0), new BlockPos(7, 1, 0)
        );
        // 5 格：A 两列下层满了（2 格），B 两列下层也应满（2 格），余 1 格流进 C 的底部。
        ShipPocketWater.Shape shape = ShipPocketWater.compute(cells,
                List.of(new BlockPos(0, 0, 0)), 500.0D);

        assertEquals(1.0F, shape.levelAt(1, 0), 0.06F, "A 应该到孔底");
        assertEquals(1.0F, shape.levelAt(3, 0), 0.08F, "B 应该被第一个孔灌到孔底");
        assertEquals(0.5F, shape.levelAt(6, 0), 0.10F,
                "C 只能拿到溢出第二个孔的水，不能和 A/B 一起涨");
        assertFalse(wet(shape, new BlockPos(0, 1, 0)), "第一个孔以上还没水");
        assertFalse(wet(shape, new BlockPos(6, 1, 0)), "第三个舱的孔以上也没水");
    }

    @Test
    @DisplayName("破口在高处：水沿竖直柱落到舱底，不会先湿上方空气")
    void highBreach_fallsToFloor() {
        Set<BlockPos> cells = Set.of(
                new BlockPos(0, 0, 0), new BlockPos(1, 0, 0),
                new BlockPos(0, 1, 0), new BlockPos(1, 1, 0)
        );
        ShipPocketWater.Shape shape = ShipPocketWater.compute(cells,
                List.of(new BlockPos(0, 1, 0)), 100.0D);

        assertTrue(wet(shape, new BlockPos(0, 0, 0)), "破口正下方应该先湿");
        assertTrue(wet(shape, new BlockPos(1, 0, 0)), "水在底层铺开");
        assertFalse(wet(shape, new BlockPos(0, 1, 0)), "上层空气不算水");
        assertFalse(wet(shape, new BlockPos(1, 1, 0)), "上层空气不算水");
    }
}
