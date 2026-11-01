package com.opamb.barotrauma.ship;

import com.opamb.barotrauma.blockentity.ShipCoreBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.jetbrains.annotations.Nullable;

/**
 * 船核心的查找 / 重扫辅助。
 */
public final class ShipCores {

    private ShipCores() {
    }

    /** 让 {@code center} 附近的船核心重新扫描（开合门、破口之后用）。 */
    public static int rescanNear(Level level, BlockPos center, int radius) {
        int scanned = 0;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius),
                center.offset(radius, radius, radius))) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ShipCoreBlockEntity core) {
                core.rescan(level);
                scanned++;
            }
        }
        return scanned;
    }

    /**
     * 找离 {@code center} 最近的船核心。
     *
     * <p>不逐格查方块（那要几十万次），而是直接翻已加载区块的方块实体表——核心很少，
     * 一次几分钟区块的遍历就够了。</p>
     */
    @Nullable
    public static ShipCoreBlockEntity nearestCore(Level level, BlockPos center, double maxDistance) {
        int chunkRadius = Math.max(1, (int) Math.ceil(maxDistance / 16.0D));
        ChunkPos origin = new ChunkPos(center);
        ShipCoreBlockEntity best = null;
        double bestDistance = maxDistance * maxDistance;
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                ChunkAccess chunk = level.getChunkSource()
                        .getChunk(origin.x + dx, origin.z + dz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                for (BlockPos bePos : chunk.getBlockEntitiesPos()) {
                    BlockEntity be = chunk.getBlockEntity(bePos);
                    if (be instanceof ShipCoreBlockEntity core) {
                        double distance = core.getBlockPos().distSqr(center);
                        if (distance <= bestDistance) {
                            bestDistance = distance;
                            best = core;
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * 解析"我所在的船"：先看提示坐标上是不是真有核心（船没动时最准），
     * 船正在开、核心已经挪走时退回"离玩家最近的核心"。
     */
    @Nullable
    public static ShipCoreBlockEntity resolve(Level level, @Nullable BlockPos hint, BlockPos fallback) {
        if (hint != null && level.getBlockEntity(hint) instanceof ShipCoreBlockEntity core) {
            return core;
        }
        return nearestCore(level, fallback, 32.0D);
    }
}
