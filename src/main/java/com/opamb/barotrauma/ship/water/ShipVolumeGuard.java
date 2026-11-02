package com.opamb.barotrauma.ship.water;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Collection;

/**
 * 物理船的"舱室流体禁区"：舱室格子不许被 MC 水写进来。
 *
 * <p>VS2 把船体方块搬进船坞后，船所在的世界位置没有方块；四周海水会立刻灌满。
 * 之前的做法是往船壳位置填不可见的排水方块——但方块是整格的、船是连续渲染的，
 * 船 y 不是整数时会露出"半格"，用户明确不接受。</p>
 *
 * <p>现在只在 {@code Level#setBlock} 入口拦截"写进舱室格子"的水：
 * 舱内保持真正的空气，船壳格则让海水自然填满——这样透过破口看出去时，
 * 水是贴着船壳的，不会在船周围留一圈干空气。舱内水位由
 * {@code ShipSimulation} + 客户端自绘水面负责，和方块水的整格边界解耦。</p>
 *
 * <p>只有一艘物理船（VsPilot 是单例），所以用一个 volatile 记录就够。
 * 记录的是<strong>装配原坐标</strong>的舱室格；船移动时只改整数偏移，
 * 不重建集合。</p>
 */
public final class ShipVolumeGuard {

    private static volatile Guard active;

    private ShipVolumeGuard() {
    }

    private record Guard(ServerLevel level, LongOpenHashSet cells,
                         int offsetX, int offsetY, int offsetZ) {
    }

    /** 装配成功后登记舱室格子（原世界坐标）。 */
    public static void activate(ServerLevel level, Collection<BlockPos> cells) {
        active = new Guard(level, pack(cells), 0, 0, 0);
    }

    /** 船体破损、舱室重新划分后更新格集（保留当前偏移）。 */
    public static void setCells(Collection<BlockPos> cells) {
        Guard guard = active;
        if (guard == null) {
            return;
        }
        active = new Guard(guard.level(), pack(cells), guard.offsetX(), guard.offsetY(), guard.offsetZ());
    }

    /** 船移动后按整数偏移整体平移禁区（相对装配原点的偏移）。 */
    public static void setOffset(int dx, int dy, int dz) {
        Guard guard = active;
        if (guard == null) {
            return;
        }
        active = new Guard(guard.level(), guard.cells(), dx, dy, dz);
    }

    /** 拆船/船丢失时解除。 */
    public static void deactivate() {
        active = null;
    }

    /** 这一格是否在物理船的舱室里（只对登记的那个维度生效）。 */
    public static boolean isProtected(ServerLevel level, BlockPos pos) {
        Guard guard = active;
        if (guard == null || guard.level() != level) {
            return false;
        }
        long key = BlockPos.asLong(
                pos.getX() - guard.offsetX(),
                pos.getY() - guard.offsetY(),
                pos.getZ() - guard.offsetZ());
        return guard.cells().contains(key);
    }

    private static LongOpenHashSet pack(Collection<BlockPos> cells) {
        LongOpenHashSet set = new LongOpenHashSet(Math.max(16, cells.size() * 2));
        for (BlockPos pos : cells) {
            set.add(BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ()));
        }
        return set;
    }
}
