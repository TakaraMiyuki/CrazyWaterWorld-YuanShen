package com.opamb.barotrauma.ship.vs;

import com.opamb.barotrauma.registry.ModShipBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 物理船的"排水体"：把船壳方块占的位置用不可见方块填死，别让海水灌进来。
 * 舱室格子不填——舱内保持真正的空气（用户要求：舱内必须能放方块、能交互）。
 *
 * <h2>为什么需要这个东西</h2>
 *
 * <p>VS2 装配一艘船的动作是：把方块从世界里挖走 → 铺到船坞区块里当刚体的体素。
 * 世界里那一块就变成了一个<b>空腔</b>。潜艇在深海，四周全是真水方块，空腔一两秒内
 * 就被海水灌满（实测：舱室 267 格、船体 408 格，<b>100% 变水</b>）。</p>
 *
 * <p>而船本身是渲染在船坞区块上的，正好渲染在这个空腔的位置——于是站在船里往外看，
 * 看到的就是"船舱里全是水"。用户报的"切换状态依旧会进水"就是这个。拆船的时候更糟：
 * 方块按快照铺回来，但那一圈水已经在了，船一回来就是满水的沉船。</p>
 *
 * <h2>怎么解决</h2>
 *
 * <p>用 {@code barotrauma:hull_plug} 这个方块把船占的包围盒填死。它满足三个条件：</p>
 * <ul>
 *   <li><b>不可见</b>——模型里一个 element 都没有，玩家看不到任何方块；</li>
 *   <li><b>不碰撞</b>（{@code noCollission}）——船里的玩家照常走动，不会被卡住；</li>
 *   <li><b>挡水</b>——它就是普通方块，水不能流进去，空腔里就一直是空气。</li>
 * </ul>
 *
 * <p>船是刚体，会整格移动，所以排水体也要跟着整格挪。为了不每 tick 重铺 600 多格，
 * 只在"整数偏移变了"的时候做一次<b>差集</b>：把不再覆盖的格子挖掉，把新覆盖的格子填上。
 * 沿一个轴挪一格只需要改两个面（约 {@code 2 × 断面} 格）。</p>
 */
public final class HullPlug {

    private static final BlockState PLUG = ModShipBlocks.HULL_PLUG.get().defaultBlockState();
    /** 腾出来的格子填回海水，而不是空气——否则船开走后会留下一条看不见的干燥尾迹。 */
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();

    /** 船的体积形状，相对 {@link #base} 的偏移。装船时算一次，之后不变。 */
    private static List<BlockPos> shape = List.of();
    /** 装配时体积的最小角，形状偏移的原点。 */
    private static BlockPos base;
    /** 当前真实铺在世界里的格子（绝对坐标）。 */
    private static Set<BlockPos> placed = Set.of();
    /** 当前用的整数偏移，和 {@link #placed} 配套。 */
    private static int offsetX;
    private static int offsetY;
    private static int offsetZ;
    /** 铺过多少格（诊断用）。 */
    private static int installedCells;

    private HullPlug() {
    }

    /** 现在有没有在铺。 */
    public static boolean active() {
        return base != null;
    }

    public static int installedCells() {
        return installedCells;
    }

    /**
     * 按"船实际占的格子"铺排水体（船体方块 + 密闭舱室），不是整个包围盒。
     *
     * <p>为什么不用包围盒：船不是实心长方体，包围盒会把船外面一圈水一起清掉，
     * 玩家看到的就是"潜艇附近的水被挖了一块"（用户反馈"清空潜艇附近的水，太割裂"）。
     * 只盖船体和舱室，船体轮廓以外的水一格都不动。</p>
     *
     * @param cells 船体方块 + 舱室格子的绝对坐标
     */
    public static void install(Level level, Collection<BlockPos> cells) {
        remove(level);
        if (cells.isEmpty()) {
            return;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (BlockPos pos : cells) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
        }
        base = new BlockPos(minX, minY, minZ);
        List<BlockPos> shapeCells = new ArrayList<>(cells.size());
        for (BlockPos pos : cells) {
            shapeCells.add(new BlockPos(pos.getX() - minX, pos.getY() - minY, pos.getZ() - minZ));
        }
        shape = shapeCells;
        offsetX = 0;
        offsetY = 0;
        offsetZ = 0;
        placed = place(level, 0, 0, 0);
        installedCells = placed.size();
    }

    /**
     * 船整格挪了，排水体跟着挪。
     *
     * <p>做成差集：{@code 旧集合 − 新集合} 挖掉，{@code 新集合 − 旧集合} 填上。
     * 所以只在整数偏移真的变了时才调它。</p>
     */
    public static void moveTo(Level level, int dx, int dy, int dz) {
        if (base == null) {
            return;
        }
        if (dx == offsetX && dy == offsetY && dz == offsetZ) {
            return;
        }
        Set<BlockPos> next = absolute(dx, dy, dz);
        // 先挖掉不再覆盖的——填回海水（不是空气），否则船尾会拖一条干燥的尾迹
        for (BlockPos pos : placed) {
            if (!next.contains(pos) && level.getBlockState(pos).is(ModShipBlocks.HULL_PLUG.get())) {
                level.setBlock(pos, WATER, 2);
            }
        }
        // 再填新覆盖的
        for (BlockPos pos : next) {
            if (!placed.contains(pos)) {
                BlockState existing = level.getBlockState(pos);
                if (existing.isAir() || existing.is(Blocks.WATER)) {
                    level.setBlock(pos, PLUG, 2);
                }
            }
        }
        placed = next;
        offsetX = dx;
        offsetY = dy;
        offsetZ = dz;
    }

    /**
     * 保证当前船位内该有排水体的格子都有。
     *
     * <p>为什么需要每 tick 补一次：实测 VS2 的装配协程在 {@code assembleToShip} 返回之后
     * 还会异步清一遍源区域——无头服务器上它等船坞区块开始 tick 会超时，超时后"强行继续"
     * 的那一步把刚铺好的 125 格排水体全部清掉了（隔离自检里装配后 1 秒内就消失）。
     * 只在偏移变化时挪一次不够，必须每 tick 把缺的补回来。</p>
     */
    public static void ensure(Level level) {
        if (base == null) {
            return;
        }
        for (BlockPos offset : shape) {
            BlockPos pos = base.offset(offset.getX() + offsetX, offset.getY() + offsetY, offset.getZ() + offsetZ);
            BlockState existing = level.getBlockState(pos);
            if (existing.isAir() || existing.is(Blocks.WATER)) {
                level.setBlock(pos, PLUG, 2);
                placed.add(pos);
            }
        }
    }

    /** 撤掉全部排水体（拆船 / 船丢了的时候）。腾出来的格子回填海水，不留干燥空腔。 */
    public static void remove(Level level) {
        if (level != null) {
            for (BlockPos pos : placed) {
                if (level.getBlockState(pos).is(ModShipBlocks.HULL_PLUG.get())) {
                    level.setBlock(pos, WATER, 2);
                }
            }
        }
        shape = List.of();
        base = null;
        placed = Set.of();
        offsetX = 0;
        offsetY = 0;
        offsetZ = 0;
        installedCells = 0;
    }

    /** 排水体现在盖住了多少格（诊断用）。 */
    public static String status() {
        if (base == null) {
            return "排水体=无";
        }
        return String.format(java.util.Locale.ROOT,
                "排水体=%d 格（形状 %d 格，偏移 %+d,%+d,%+d）",
                placed.size(), shape.size(), offsetX, offsetY, offsetZ);
    }

    private static Set<BlockPos> place(Level level, int dx, int dy, int dz) {
        Set<BlockPos> out = new HashSet<>();
        for (BlockPos offset : shape) {
            BlockPos pos = base.offset(offset.getX() + dx, offset.getY() + dy, offset.getZ() + dz);
            BlockState existing = level.getBlockState(pos);
            if (existing.isAir() || existing.is(Blocks.WATER)) {
                level.setBlock(pos, PLUG, 2);
                out.add(pos);
            }
        }
        return out;
    }

    private static Set<BlockPos> absolute(int dx, int dy, int dz) {
        Set<BlockPos> out = new HashSet<>(shape.size() * 2);
        for (BlockPos offset : shape) {
            out.add(base.offset(offset.getX() + dx, offset.getY() + dy, offset.getZ() + dz));
        }
        return out;
    }
}
