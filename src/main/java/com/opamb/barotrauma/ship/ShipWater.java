package com.opamb.barotrauma.ship;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 把"舱室水位"翻译成真实水方块。
 *
 * <p>《潜渊症》的水不是流体模拟：每个舱室只有一个水位。这里按水位从舱底往上铺水——
 * 水位涨一格就多一格水，玩家能看见水线上升。连到外海的空腔（船体破洞）直接按满水处理。</p>
 */
public final class ShipWater {

    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** 一个舱室（或破口空腔）的格子，已按"从底往上"排好序。 */
    private record Room(List<BlockPos> cells, int volume, int compartmentIndex, int pocketIndex,
                        boolean openToSea) {
    }

    private final List<Room> rooms;
    private final ShipSimulation simulation;

    public ShipWater(ShipGeometry.Result geometry, ShipSimulation simulation) {
        this.simulation = simulation;
        this.rooms = new ArrayList<>();
        int index = 0;
        for (ShipGeometry.Compartment compartment : geometry.compartments()) {
            rooms.add(new Room(sorted(compartment.cells()), compartment.volume(), index++, -1, false));
        }
        int pocketIndex = 0;
        for (ShipGeometry.Compartment pocket : geometry.openPockets()) {
            rooms.add(new Room(sorted(pocket.cells()), pocket.volume(), -1, pocketIndex++, true));
        }
    }

    private static List<BlockPos> sorted(Iterable<BlockPos> cells) {
        List<BlockPos> list = new ArrayList<>();
        cells.forEach(list::add);
        list.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));
        return list;
    }

    /** 按当前水位重写水方块。返回改动的方块数。 */
    public int apply(Level level) {
        int changed = 0;
        for (Room room : rooms) {
            int filled;
            if (room.openToSea) {
                // 破口进水是渐进的（ShipSimulation.tickSeaFlood），不再直接按满水
                filled = (int) Math.floor(simulation.pocketWater(room.pocketIndex()) / ShipSimulation.UNITS_PER_BLOCK);
            } else {
                double units = room.compartmentIndex >= 0 ? simulation.waterUnits(room.compartmentIndex) : 0.0D;
                filled = (int) Math.floor(units / ShipSimulation.UNITS_PER_BLOCK);
            }
            if (filled < 0) {
                filled = 0;
            } else if (filled > room.volume) {
                filled = room.volume;
            }
            for (int i = 0; i < room.cells.size(); i++) {
                BlockPos pos = room.cells.get(i);
                BlockState state = level.getBlockState(pos);
                boolean shouldBeWater = i < filled;
                if (shouldBeWater && state.isAir()) {
                    level.setBlock(pos, WATER, 3);
                    changed++;
                } else if (!shouldBeWater && state.is(Blocks.WATER)) {
                    level.setBlock(pos, AIR, 3);
                    changed++;
                }
            }
        }
        return changed;
    }

    /**
     * 把船内所有"我们写进去的水"变回空气。
     *
     * <p>只清舱室和连海空腔里的水，船体方块一格不碰。装船前调用，
     * 免得装配之后原来的位置陷下一坨水。</p>
     */
    public int clear(Level level) {
        return clear(level, 0, 0, 0);
    }

    /** 带整数位移的版本：拆船时方块铺回的是"原坐标 + 船位移"。 */
    public int clear(Level level, int dx, int dy, int dz) {
        int changed = 0;
        for (Room room : rooms) {
            for (BlockPos pos : room.cells) {
                BlockPos target = pos.offset(dx, dy, dz);
                if (level.getBlockState(target).is(Blocks.WATER)) {
                    level.setBlock(target, AIR, 3);
                    changed++;
                }
            }
        }
        return changed;
    }
}
