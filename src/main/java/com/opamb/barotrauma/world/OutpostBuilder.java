package com.opamb.barotrauma.world;

import com.opamb.barotrauma.registry.ModShipBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * 新手哨站（M1 版本）：一个 40×21×24 的气密盒子，内部分成前厅 / 医疗 / 补给三间。
 *
 * <p>生成方式是"按坐标算方块"，每个区块只写和自己相交的部分，所以可以跨区块无缝拼起来。
 * 内墙都是双层装甲板，只有气闸门洞例外——门用橡木门（可以手开，关着的时候挡水），
 * 玩家一开门，海水就会灌进来，这就是《潜渊症》的进水机制。</p>
 */
public final class OutpostBuilder {

    /** 哨站外墙半径。 */
    public static final int HALF_X = 20;
    public static final int HALF_Z = 12;
    /** 外墙 y 范围。 */
    public static final int MIN_Y = -160;
    public static final int MAX_Y = -140;

    /** 内部空腔范围（玩家活动的空间）。 */
    private static final int INNER_HALF_X = 18;
    private static final int INNER_HALF_Z = 10;
    private static final int INNER_MIN_Y = -158;
    private static final int INNER_MAX_Y = -142;

    private OutpostBuilder() {
    }

    public static boolean intersects(ChunkPos pos) {
        return pos.getMaxBlockX() >= -HALF_X && pos.getMinBlockX() <= HALF_X
                && pos.getMaxBlockZ() >= -HALF_Z && pos.getMinBlockZ() <= HALF_Z;
    }

    /** 哨站水池的判定范围（比建筑本体大）。 */
    public static boolean intersectsCavern(ChunkPos pos) {
        return pos.getMaxBlockX() >= -36 && pos.getMinBlockX() <= 36
                && pos.getMaxBlockZ() >= -36 && pos.getMinBlockZ() <= 36;
    }

    /**
     * 在哨站外面挖一个椭球水池：半径 34×26×34，中心就是哨站中心。
     * 这样气闸门推开外面确实是水，而不是一堵岩壁。
     */
    public static void carveCavern(ChunkAccess chunk) {
        ChunkPos pos = chunk.getPos();
        BlockState water = Blocks.WATER.defaultBlockState();
        for (int x = pos.getMinBlockX(); x <= pos.getMaxBlockX(); x++) {
            for (int z = pos.getMinBlockZ(); z <= pos.getMaxBlockZ(); z++) {
                for (int y = -176; y <= -124; y++) {
                    double dx = x / 34.0D;
                    double dy = (y + 150) / 26.0D;
                    double dz = z / 34.0D;
                    if (dx * dx + dy * dy + dz * dz < 1.0D) {
                        chunk.setBlockState(new BlockPos(x, y, z), water, false);
                    }
                }
            }
        }
    }

    public static void build(ChunkAccess chunk) {
        ChunkPos pos = chunk.getPos();
        int x0 = Math.max(pos.getMinBlockX(), -HALF_X);
        int x1 = Math.min(pos.getMaxBlockX(), HALF_X);
        int z0 = Math.max(pos.getMinBlockZ(), -HALF_Z);
        int z1 = Math.min(pos.getMaxBlockZ(), HALF_Z);

        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = MIN_Y; y <= MAX_Y; y++) {
                    BlockState state = stateAt(x, y, z);
                    if (state != null) {
                        chunk.setBlockState(new BlockPos(x, y, z), state, false);
                    }
                }
            }
        }

        // 外部门口平台（在水里，出门就是海）
        BlockState plating = plating();
        for (int x = -3; x <= 3; x++) {
            for (int z = HALF_Z + 1; z <= HALF_Z + 5; z++) {
                if (inChunk(pos, x, z)) {
                    chunk.setBlockState(new BlockPos(x, -159, z), plating, false);
                }
            }
        }

        // 气闸门（两格高）
        if (inChunk(pos, 0, HALF_Z)) {
            placeDoor(chunk, 0, ModDimensions.OUTPOST_SPAWN_Y, HALF_Z);
        }
    }

    private static BlockState stateAt(int x, int y, int z) {
        if (Math.abs(x) > HALF_X || Math.abs(z) > HALF_Z || y < MIN_Y || y > MAX_Y) {
            return null;
        }

        boolean interior = Math.abs(x) <= INNER_HALF_X
                && Math.abs(z) <= INNER_HALF_Z
                && y >= INNER_MIN_Y && y <= INNER_MAX_Y;

        if (interior) {
            // 隔墙：把内部切成三间，中间 |z|<=1 且下三格留门洞
            if (Math.abs(x) == 6 && y >= INNER_MIN_Y && y <= INNER_MAX_Y) {
                boolean doorway = Math.abs(z) <= 1 && y <= INNER_MIN_Y + 2;
                if (!doorway) {
                    return plating();
                }
            }
            // 天花板灯
            if (y == INNER_MAX_Y && Math.floorMod(x, 6) == 0 && Math.floorMod(z, 6) == 0) {
                return Blocks.SEA_LANTERN.defaultBlockState();
            }
            return Blocks.AIR.defaultBlockState();
        }

        // 气闸门洞：把外墙挖穿，等下面放门
        if (x == 0 && z == HALF_Z && (y == ModDimensions.OUTPOST_SPAWN_Y
                || y == ModDimensions.OUTPOST_SPAWN_Y + 1)) {
            return Blocks.AIR.defaultBlockState();
        }

        return plating();
    }

    private static void placeDoor(ChunkAccess chunk, int x, int y, int z) {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.NORTH)
                .setValue(DoorBlock.OPEN, Boolean.FALSE)
                .setValue(DoorBlock.POWERED, Boolean.FALSE)
                .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        chunk.setBlockState(new BlockPos(x, y, z), lower, false);
        chunk.setBlockState(new BlockPos(x, y + 1, z), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), false);
    }

    private static BlockState plating() {
        // 站点用专属装甲，避免被船核心当成"船体"扫进去
        return ModShipBlocks.STATION_PLATING.get().defaultBlockState();
    }

    private static boolean inChunk(ChunkPos pos, int x, int z) {
        return x >= pos.getMinBlockX() && x <= pos.getMaxBlockX()
                && z >= pos.getMinBlockZ() && z <= pos.getMaxBlockZ();
    }
}
