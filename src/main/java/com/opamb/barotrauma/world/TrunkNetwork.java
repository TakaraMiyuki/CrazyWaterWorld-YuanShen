package com.opamb.barotrauma.world;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 主干道网络：贯穿整根水柱的长隧道。
 *
 * <p>做法是"抖动网格图"：每 {@link #SPACING} 格放一个节点，节点在 XZ 上随机偏 30 格以内，
 * 每个节点向 +X 和 +Z 方向的邻居连一条管道，于是整张图在无限范围内连通、到每个节点都会分叉。
 * 每 {@link #BAND_SPACING} 米一层，层与层之间按概率打竖井，形成三维网络。</p>
 *
 * <p>所有位置都由世界种子哈希出来，所以跨区块、跨存档都一致，不需要额外存档数据。</p>
 */
public final class TrunkNetwork {

    /** 节点网格间距（格）。加大让走廊更长更直，少点急转弯。 */
    public static final int SPACING = 160;
    /** 第一层主干道的深度（y）。 */
    public static final int BAND_START = -130;
    /** 层间距（格 = 米）。 */
    public static final int BAND_SPACING = 120;
    /** 层数：-130 ~ -970。 */
    public static final int BAND_COUNT = 8;

    /** 主干道半径（水平），直径约 24 格——潜艇 9×5×15 能舒服地在里面掉头。 */
    public static final double RADIUS = 12.0;
    /** 垂直方向更宽敞，方便以后开潜艇。 */
    private static final double HEIGHT_SCALE = 1.35;
    private static final double SHAFT_RADIUS = 10.0;
    /** 竖井概率。原来是 0.18，上下层之间经常找不到路，提到 0.45。 */
    private static final double SHAFT_CHANCE = 0.45;

    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final AtomicLong TOTAL_CARVED = new AtomicLong();

    /**
     * 哨站旁的固定竖井。
     *
     * <p>主干道是哈希出来的，玩家出了站能不能正好碰上竖井全看运气。这条是写死的：
     * 贴着哨站水池（x ±36）往东开口，一路挖到 -960，保证"从出生点直接往下开"这条路永远存在。</p>
     */
    private static final double HUB_X = 44.0D;
    private static final double HUB_Z = 0.0D;
    private static final double HUB_RADIUS = 12.0D;
    private static final int HUB_BOTTOM = -960;

    private TrunkNetwork() {
    }

    public static long totalCarved() {
        return TOTAL_CARVED.get();
    }

    /** 把和本区块相交的主干道挖出来（挖成水）。返回本区块挖掉的方块数。 */
    public static int carveInto(ChunkAccess chunk, long seed) {
        ChunkPos pos = chunk.getPos();
        int margin = (int) Math.ceil(RADIUS) + 1;
        int i0 = Math.floorDiv(pos.getMinBlockX() - margin, SPACING) - 1;
        int i1 = Math.floorDiv(pos.getMaxBlockX() + margin, SPACING) + 1;
        int j0 = Math.floorDiv(pos.getMinBlockZ() - margin, SPACING) - 1;
        int j1 = Math.floorDiv(pos.getMaxBlockZ() + margin, SPACING) + 1;

        int carved = 0;
        // 固定的哨站竖井，先挖它，保证这条路一定存在（弯曲的，不是电梯井）
        carved += carveWindingShaft(chunk, seed, HUB_X, BAND_START, HUB_Z, HUB_BOTTOM, HUB_RADIUS, 91);

        for (int band = 0; band < BAND_COUNT; band++) {
            for (int i = i0; i <= i1; i++) {
                for (int j = j0; j <= j1; j++) {
                    double ax = nodeX(seed, i, j, band);
                    double ay = nodeY(seed, i, j, band);
                    double az = nodeZ(seed, i, j, band);

                    // 竖井：连到下一层同一格点。层内本来就是大片开阔水域，
                    // 所以这里只要竖井，不再挖水平走廊——走廊会把"上下岩壁"掏成筛子。
                    if (band + 1 < BAND_COUNT && rand01(seed, i, j, band, 9) < SHAFT_CHANCE) {
                        carved += carveWindingShaft(chunk, seed,
                                ax, ay, az,
                                nodeY(seed, i, j, band + 1),
                                SHAFT_RADIUS, (band + 1) * 131 + i * 17 + j);
                    }
                }
            }
        }
        TOTAL_CARVED.addAndGet(carved);
        return carved;
    }

    /**
     * 挖一条"弯曲的竖井"：把一条直线拆成若干段，每段水平位置随机偏一点。
     *
     * <p>直筒圆柱在岩石里看着像电梯井，很出戏；原版的垂直洞穴是弯的、宽窄不一的。</p>
     */
    private static int carveWindingShaft(ChunkAccess chunk, long seed, double x, double y, double z,
                                         double endY, double radius, int salt) {
        int segments = 6;
        double cx = x;
        double cy = y;
        double cz = z;
        int carved = 0;
        for (int s = 0; s < segments; s++) {
            double nextY = cy + (endY - cy) / (segments - s);
            double nextX = x + (rand01(seed, salt, s, 0, 3) - 0.5D) * radius * 2.2D;
            double nextZ = z + (rand01(seed, salt, s, 0, 5) - 0.5D) * radius * 2.2D;
            double r = radius * (0.75D + rand01(seed, salt, s, 1, 7) * 0.5D);
            carved += carveSegment(chunk, cx, cy, cz, nextX, nextY, nextZ, r);
            cx = nextX;
            cy = nextY;
            cz = nextZ;
        }
        return carved;
    }

    private static int carveSegment(ChunkAccess chunk, double ax, double ay, double az,
                                    double bx, double by, double bz, double radius) {
        int minX = chunk.getPos().getMinBlockX();
        int maxX = minX + 15;
        int minZ = chunk.getPos().getMinBlockZ();
        int maxZ = minZ + 15;
        int minY = Math.max(chunk.getMinBuildHeight(), ModDimensions.WATER_BOTTOM_Y);
        int maxY = Math.min(chunk.getMinBuildHeight() + chunk.getHeight() - 1, ModDimensions.WATER_TOP_Y);

        int x0 = Math.max(minX, (int) Math.floor(Math.min(ax, bx) - radius));
        int x1 = Math.min(maxX, (int) Math.floor(Math.max(ax, bx) + radius));
        int y0 = Math.max(minY, (int) Math.floor(Math.min(ay, by) - radius * HEIGHT_SCALE));
        int y1 = Math.min(maxY, (int) Math.floor(Math.max(ay, by) + radius * HEIGHT_SCALE));
        int z0 = Math.max(minZ, (int) Math.floor(Math.min(az, bz) - radius));
        int z1 = Math.min(maxZ, (int) Math.floor(Math.max(az, bz) + radius));
        if (x0 > x1 || y0 > y1 || z0 > z1) {
            return 0;
        }

        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        double lengthSq = dx * dx + dy * dy + dz * dz;
        if (lengthSq < 1.0E-4D) {
            return 0;
        }
        double radiusSq = radius * radius;
        double heightRadiusSq = radius * HEIGHT_SCALE * radius * HEIGHT_SCALE;

        int carved = 0;
        for (int x = x0; x <= x1; x++) {
            double px = (x + 0.5D) - ax;
            for (int z = z0; z <= z1; z++) {
                double pz = (z + 0.5D) - az;
                for (int y = y0; y <= y1; y++) {
                    double py = (y + 0.5D) - ay;
                    double t = (px * dx + py * dy + pz * dz) / lengthSq;
                    if (t < 0.0D) {
                        t = 0.0D;
                    } else if (t > 1.0D) {
                        t = 1.0D;
                    }
                    double ex = px - dx * t;
                    double ey = py - dy * t;
                    double ez = pz - dz * t;
                    double q = (ex * ex + ez * ez) / radiusSq + (ey * ey) / heightRadiusSq;
                    if (q > 1.0D) {
                        continue;
                    }
                    if (setWater(chunk, x, y, z)) {
                        carved++;
                    }
                }
            }
        }
        return carved;
    }

    private static boolean setWater(ChunkAccess chunk, int x, int y, int z) {
        int sectionIndex = (y - chunk.getMinBuildHeight()) >> 4;
        LevelChunkSection section = chunk.getSection(sectionIndex);
        int lx = x & 15;
        int ly = y & 15;
        int lz = z & 15;
        if (section.getBlockState(lx, ly, lz).is(Blocks.WATER)) {
            return false;
        }
        section.setBlockState(lx, ly, lz, WATER, false);
        return true;
    }

    // ------------------------------------------------------------------
    // 节点坐标：全部由种子 + 格点索引哈希得到
    // ------------------------------------------------------------------

    private static double nodeX(long seed, int i, int j, int band) {
        return i * (double) SPACING + (rand01(seed, i, j, band, 1) - 0.5D) * (SPACING * 0.45D);
    }

    private static double nodeY(long seed, int i, int j, int band) {
        return BAND_START + band * (double) BAND_SPACING
                + (rand01(seed, i, j, band, 2) - 0.5D) * (BAND_SPACING * 0.30D);
    }

    private static double nodeZ(long seed, int i, int j, int band) {
        return j * (double) SPACING + (rand01(seed, i, j, band, 3) - 0.5D) * (SPACING * 0.45D);
    }

    private static double rand01(long seed, int i, int j, int band, int salt) {
        long h = seed
                ^ (i * 0x9E3779B97F4A7C15L)
                ^ (j * 0xC2B2AE3D27D4EB4FL)
                ^ ((long) band * 0x165667B19E3779F9L)
                ^ ((long) salt * 0x27D4EB2F165667C5L);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53D;
    }
}
