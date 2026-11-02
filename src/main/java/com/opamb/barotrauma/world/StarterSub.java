package com.opamb.barotrauma.world;

import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.blockentity.ShipCoreBlockEntity;
import com.opamb.barotrauma.registry.ModBlocks;
import com.opamb.barotrauma.registry.ModShipBlocks;
import com.opamb.barotrauma.ship.vs.VsState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 初始潜艇：停在新手哨站门口那艘。
 *
 * <p>对应原版流程里"出生在站点、站点旁边连着你的第一艘船"。9×5×15 的小艇，
 * 甲板下一排机器（反应堆 / 制氧机 / 水泵 / 压载舱 / 引擎 / 驾驶台 / 声纳），
 * 中间放船核心，顶上有一个舱口和两扇舷窗。</p>
 *
 * <p>位置在前哨站水池的椭球范围内（见 {@code OutpostBuilder.carveCavern}），所以整艘都泡在水里。</p>
 */
public final class StarterSub {

    /** 最小角坐标。船体占 x −4..4、y −72..−68、z 16..30（泡在哨站水池椭球里，见 OutpostBuilder）。 */
    public static final int ORIGIN_X = -4;
    public static final int ORIGIN_Y = -72;
    public static final int ORIGIN_Z = 16;
    public static final int SIZE_X = 9;
    public static final int SIZE_Y = 5;
    public static final int SIZE_Z = 15;

    private StarterSub() {
    }

    /**
     * 船脑位置 = 声纳台的位置。
     *
     * <p>声纳台现在兼任船核心（{@code SonarBlockEntity extends ShipCoreBlockEntity}），
     * 不再有独立的船核心方块。</p>
     */
    public static BlockPos corePos() {
        return new BlockPos(ORIGIN_X + 5, ORIGIN_Y + 1, ORIGIN_Z + 4);
    }

    /** 船核心的世界坐标（给提示文字用）。 */
    public static BlockPos doorPos() {
        return new BlockPos(ORIGIN_X + 4, ORIGIN_Y + 1, ORIGIN_Z);
    }

    /**
     * 造好初始潜艇并扫描一次。已经造过就直接返回现有的核心。
     */
    @Nullable
    public static ShipCoreBlockEntity ensure(ServerLevel level) {
        BlockPos corePos = corePos();
        // 这艘船已经被收成 VS2 物理刚体了：方块搬到船坞区块里、世界里这一格是空气。
        // 如果这里还照旧"没看到声纳就现造一艘"，就会在物理船旁边凭空长出一艘方块潜艇，
        // 用户看到的就是"两套移动系统叠在一起 / 方块没删干净"。
        if (VsState.active()) {
            BlockPos activeCore = VsState.corePos();
            // 只有"被收成物理船的就是这艘初始潜艇"才跳过；玩家先装了别的船时，
            // 初始潜艇仍然应该出现在哨站门口。
            if (corePos.equals(activeCore)) {
                BarotraumaMod.LOGGER.info("[Barotrauma] 初始潜艇已收成物理船，跳过重建");
                return null;
            }
            BarotraumaMod.LOGGER.info("[Barotrauma] 当前物理船不是初始潜艇（core={}），继续检查初始潜艇",
                    activeCore);
        }
        // 必须先把区块加载出来：Level#getBlockEntity 对未加载区块返回 null，
        // 世界重开后船其实还在，却会被判成"没造过"。
        level.getChunk(corePos.getX() >> 4, corePos.getZ() >> 4);
        if (level.getBlockState(corePos).is(ModShipBlocks.SONAR.get())) {
            if (level.getBlockEntity(corePos) instanceof ShipCoreBlockEntity existing) {
                if (existing.state() == null) {
                    existing.rescan(level);
                }
                return existing;
            }
        }
        // 声纳没了但船体还在（被打了、或者核心被拆走）——**不要重建**，
        // 否则同一片海面上会出现两艘叠在一起的初始潜艇
        if (hasRemainingHull(level)) {
            BarotraumaMod.LOGGER.warn("[Barotrauma] {} 处没有声纳台，但船体还在，跳过重建", corePos);
            return null;
        }

        // 船壳用不透明装甲板：物理船是连续坐标渲染、排水体是整格方块，
        // 船 y 不是整数时两者在船壳处差半格——透明船壳会把"半个方块"的水/缺口暴露出来。
        // 飞机舷窗用玻璃（HULL_GLASS），既能看到外面，又不会把整艘船做成透明壳。
        BlockState hull = ModBlocks.SUBMARINE_PLATING.get().defaultBlockState();
        BlockState glass = ModShipBlocks.HULL_GLASS.get().defaultBlockState();

        for (int dx = 0; dx < SIZE_X; dx++) {
            for (int dy = 0; dy < SIZE_Y; dy++) {
                for (int dz = 0; dz < SIZE_Z; dz++) {
                    BlockPos pos = new BlockPos(ORIGIN_X + dx, ORIGIN_Y + dy, ORIGIN_Z + dz);
                    boolean shell = dx == 0 || dx == SIZE_X - 1
                            || dy == 0 || dy == SIZE_Y - 1
                            || dz == 0 || dz == SIZE_Z - 1;
                    if (!shell) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                        continue;
                    }
                    // 顶部舷窗（dx 3..5, dz 4..6）
                    boolean window = dy == SIZE_Y - 1 && dx >= 3 && dx <= 5 && dz >= 4 && dz <= 6;
                    level.setBlock(pos, window ? glass : hull, 2);
                }
            }
        }

        // 声纳台就是船脑（机器/舱门已按用户要求全部删除，船上只留船壳和声纳）
        level.setBlock(new BlockPos(ORIGIN_X + 5, ORIGIN_Y + 1, ORIGIN_Z + 4),
                ModShipBlocks.SONAR.get().defaultBlockState(), 2);

        // 声纳台就是船脑（在上面的机器布局里已经放下去了），这里直接拿它的方块实体扫一次
        if (level.getBlockEntity(corePos) instanceof ShipCoreBlockEntity core) {
            core.rescan(level);
            BarotraumaMod.LOGGER.info("[Barotrauma] 初始潜艇已建造：core={} 范围 {}x{}x{}",
                    corePos, SIZE_X, SIZE_Y, SIZE_Z);
            return core;
        }
        return null;
    }

    /** 初始潜艇的包围盒里还有没有船体方块（用来判断"船还在、只是核心没了"）。 */
    private static boolean hasRemainingHull(ServerLevel level) {
        for (BlockPos pos : BlockPos.betweenClosed(
                new BlockPos(ORIGIN_X, ORIGIN_Y, ORIGIN_Z),
                new BlockPos(ORIGIN_X + SIZE_X - 1, ORIGIN_Y + SIZE_Y - 1, ORIGIN_Z + SIZE_Z - 1))) {
            if (ModShipBlocks.isShipBlock(level.getBlockState(pos))) {
                return true;
            }
        }
        return false;
    }
}
