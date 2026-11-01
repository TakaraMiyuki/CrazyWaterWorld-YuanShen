package com.opamb.barotrauma.world;

import com.opamb.barotrauma.blockentity.ShipCoreBlockEntity;
import com.opamb.barotrauma.registry.ModBlocks;
import com.opamb.barotrauma.registry.ModShipBlocks;
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

    /** 最小角坐标。船体占 x −4..4、y −152..−148、z 16..30。 */
    public static final int ORIGIN_X = -4;
    public static final int ORIGIN_Y = -152;
    public static final int ORIGIN_Z = 16;
    public static final int SIZE_X = 9;
    public static final int SIZE_Y = 5;
    public static final int SIZE_Z = 15;

    private StarterSub() {
    }

    public static BlockPos corePos() {
        return new BlockPos(ORIGIN_X + 4, ORIGIN_Y + 2, ORIGIN_Z + 7);
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
        // 必须先把区块加载出来：Level#getBlockEntity 对未加载区块返回 null，
        // 世界重开后船其实还在，却会被判成"没造过"。
        level.getChunk(corePos.getX() >> 4, corePos.getZ() >> 4);
        if (level.getBlockState(corePos).is(ModShipBlocks.SHIP_CORE.get())) {
            if (level.getBlockEntity(corePos) instanceof ShipCoreBlockEntity existing) {
                if (existing.state() == null) {
                    existing.rescan(level);
                }
                return existing;
            }
        }

        BlockState hull = ModBlocks.SUBMARINE_PLATING.get().defaultBlockState();
        BlockState glass = ModShipBlocks.HULL_GLASS.get().defaultBlockState();
        BlockState hatch = ModShipBlocks.HATCH.get().defaultBlockState();

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
                    // 顶部舷窗（dx 3..5, dz 4..6）与舱口（dx 4, dz 11）
                    boolean window = dy == SIZE_Y - 1 && dx >= 3 && dx <= 5 && dz >= 4 && dz <= 6;
                    level.setBlock(pos, window ? glass : hull, 2);
                }
            }
        }
        level.setBlock(new BlockPos(ORIGIN_X + 4, ORIGIN_Y + SIZE_Y - 1, ORIGIN_Z + 11), hatch, 3);

        // 甲板下的机器，和自检里那艘同布局
        level.setBlock(new BlockPos(ORIGIN_X + 2, ORIGIN_Y + 1, ORIGIN_Z + 2),
                ModShipBlocks.NUCLEAR_REACTOR.get().defaultBlockState(), 2);
        level.setBlock(new BlockPos(ORIGIN_X + 3, ORIGIN_Y + 1, ORIGIN_Z + 2),
                ModShipBlocks.OXYGEN_GENERATOR.get().defaultBlockState(), 2);
        level.setBlock(new BlockPos(ORIGIN_X + 4, ORIGIN_Y + 1, ORIGIN_Z + 2),
                ModShipBlocks.PUMP.get().defaultBlockState(), 2);
        level.setBlock(new BlockPos(ORIGIN_X + 5, ORIGIN_Y + 1, ORIGIN_Z + 2),
                ModShipBlocks.BALLAST_TANK.get().defaultBlockState(), 2);
        level.setBlock(new BlockPos(ORIGIN_X + 2, ORIGIN_Y + 1, ORIGIN_Z + 4),
                ModShipBlocks.ENGINE.get().defaultBlockState(), 2);
        level.setBlock(new BlockPos(ORIGIN_X + 3, ORIGIN_Y + 1, ORIGIN_Z + 4),
                ModShipBlocks.HELM.get().defaultBlockState(), 2);
        level.setBlock(new BlockPos(ORIGIN_X + 5, ORIGIN_Y + 1, ORIGIN_Z + 4),
                ModShipBlocks.SONAR.get().defaultBlockState(), 2);
        level.setBlock(new BlockPos(ORIGIN_X + 6, ORIGIN_Y + 1, ORIGIN_Z + 2),
                ModShipBlocks.AIRLOCK_DOOR.get().defaultBlockState(), 2);

        level.setBlock(corePos, ModShipBlocks.SHIP_CORE.get().defaultBlockState(), 3);
        if (level.getBlockEntity(corePos) instanceof ShipCoreBlockEntity core) {
            core.rescan(level);
            return core;
        }
        return null;
    }
}
