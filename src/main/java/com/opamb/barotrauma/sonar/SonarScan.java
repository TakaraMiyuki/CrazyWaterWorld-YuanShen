package com.opamb.barotrauma.sonar;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Set;

/**
 * 声纳扫描：把周围空间压成一团点云。
 *
 * <p>做法是"抽稀网格 + 只留表面"：以声纳为中心按 {@code stride} 格采样一个球，
 * 先记下每格是不是实心（这一步只查一次方块），再挑出六个网格邻居里至少有一个是空的格子。
 * 这样输出的是岩壁 / 船体的**轮廓**，而不是一整块实心石头——这才像声纳看到的东西，
 * 而且查询次数从 6 倍降到 1 倍（半径 48 时约 1.6 万次）。</p>
 *
 * <p>三种模式对应原版的被动 / 主动 / 定向（见 wiki 的 Navigation Terminal）：</p>
 * <ul>
 *   <li>{@link Mode#ACTIVE}：360° 全向脉冲，打出什么就显示什么；</li>
 *   <li>{@link Mode#DIRECTIONAL}：只在 30° 锥内打，其余方向不显示；</li>
 *   <li>{@link Mode#PASSIVE}：**不发射**，只听声音，所以没有地形，只有声源（生物 / 自己）。</li>
 * </ul>
 */
public final class SonarScan {

    /** 地形（岩壁、海底）。 */
    public static final int TYPE_TERRAIN = 0;
    /** 本船船体。 */
    public static final int TYPE_HULL = 1;
    /** 活物接触点。 */
    public static final int TYPE_CONTACT = 2;

    public static final int MIN_RANGE = 16;
    /** 最大射程。原来是 64——在 90 格高的开阔海域里连上下岩壁都扫不全，用户反馈"范围太小"。 */
    public static final int MAX_RANGE = 160;
    /** 声纳默认射程（格）。 */
    public static final int DEFAULT_RANGE = 80;
    /** 一次扫描最多多少点（包大小上限）。 */
    public static final int MAX_POINTS = 4096;

    /** 定向脉冲的锥角：wiki 写的是 30 度。 */
    public static final float CONE_DEGREES = 30.0F;
    private static final float CONE_COS2 = 0.9330127F; // cos(15°)^2

    /** 声纳工作模式。 */
    public enum Mode {
        /** 360° 主动脉冲。 */
        ACTIVE,
        /** 被动：只听声音，没有地形。 */
        PASSIVE,
        /** 30° 定向脉冲。 */
        DIRECTIONAL;

        public static Mode byOrdinal(int ordinal) {
            Mode[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : ACTIVE;
        }

        public int id() {
            return ordinal();
        }
    }

    /**
     * 一个扫描帧。{@code points} 是打包后的点：高 2 位类型，其余按 x/y/z 各 8 位存偏移 +64。
     */
    public record Frame(int range, int[] points) {
        public int size() {
            return points.length;
        }
    }

    private SonarScan() {
    }

    /** 抽稀步长：射程越远格子越粗，保证每次扫描的方块查询数基本恒定。 */
    public static int strideFor(int range) {
        return Math.max(2, (int) Math.round(Mth.clamp(range, MIN_RANGE, MAX_RANGE) / 12.0D));
    }

    /** 全向扫描（主动）。 */
    public static Frame scan(Level level, BlockPos center, int range,
                             @Nullable Set<BlockPos> hull, @Nullable Entity exclude) {
        return scan(level, center, range, hull, exclude, Mode.ACTIVE, 0.0F, 0.0F, 0.0F);
    }

    public static Frame scan(Level level, BlockPos center, int range,
                             @Nullable Set<BlockPos> hull, @Nullable Entity exclude,
                             Mode mode, float dirX, float dirY, float dirZ) {
        int r = Mth.clamp(range, MIN_RANGE, MAX_RANGE);

        // 定向模式的锥向；没给方向就退化成全向
        float length = Mth.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        boolean directional = mode == Mode.DIRECTIONAL && length > 1.0E-4F;
        float ux = directional ? dirX / length : 0.0F;
        float uy = directional ? dirY / length : 0.0F;
        float uz = directional ? dirZ / length : 0.0F;

        int[] buffer = new int[MAX_POINTS];
        int count = 0;

        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();

        // ---- 地形：被动模式完全不做（不发脉冲就没有回波） ----
        if (mode != Mode.PASSIVE) {
            int stride = strideFor(r);
            int base = (r / stride) * stride;
            int n = 2 * (base / stride) + 1;

            // 三态：0 = 不知道（球外 / 区块没加载），1 = 实心，2 = 空。
            // 之前用的是 boolean 且默认 true（"球外当实心"），结果球边界那一圈全被判成"实心且有空的邻居"，
            // 屏幕上就出现一个完美的球壳——用户看到的"一转就变成球"。未加载区块同理。
            byte[] kind = new byte[n * n * n];

            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (int i = 0; i < n; i++) {
                int x = -base + i * stride;
                for (int k = 0; k < n; k++) {
                    int z = -base + k * stride;
                    if (x * x + z * z > r * r) {
                        continue;
                    }
                    // 关键：没加载的区块**不能**去读。Level#getBlockState 会同步加载区块，
                    // 一次 48 m 扫描会硬拉 49 个区块，卡掉两百毫秒。读不到就保持"不知道"。
                    if (!level.hasChunk((cx + x) >> 4, (cz + z) >> 4)) {
                        continue;
                    }
                    for (int j = 0; j < n; j++) {
                        int y = -base + j * stride;
                        if (x * x + y * y + z * z > r * r) {
                            continue;
                        }
                        cursor.set(cx + x, cy + y, cz + z);
                        BlockState state = level.getBlockState(cursor);
                        kind[index(i, j, k, n)] = (byte) (state.isAir() || !state.getFluidState().isEmpty()
                                ? EMPTY : SOLID);
                    }
                }
            }

            for (int i = 0; i < n && count < MAX_POINTS; i++) {
                for (int j = 0; j < n && count < MAX_POINTS; j++) {
                    for (int k = 0; k < n && count < MAX_POINTS; k++) {
                        if (kind[index(i, j, k, n)] != SOLID) {
                            continue;
                        }
                        // 只有"实心，且至少一个已知邻居是空"才算表面
                        if (!knownEmpty(kind, i - 1, j, k, n) && !knownEmpty(kind, i + 1, j, k, n)
                                && !knownEmpty(kind, i, j - 1, k, n) && !knownEmpty(kind, i, j + 1, k, n)
                                && !knownEmpty(kind, i, j, k - 1, n) && !knownEmpty(kind, i, j, k + 1, n)) {
                            continue;
                        }
                        int x = -base + i * stride;
                        int y = -base + j * stride;
                        int z = -base + k * stride;
                        if (directional && !inCone(x, y, z, ux, uy, uz)) {
                            continue;
                        }
                        buffer[count++] = pack(TYPE_TERRAIN, x, y, z);
                    }
                }
            }
        }

        // ---- 本船船体（精确坐标，自己永远看得见） ----
        if (hull != null) {
            for (BlockPos pos : hull) {
                if (count >= MAX_POINTS) {
                    break;
                }
                int dx = pos.getX() - cx;
                int dy = pos.getY() - cy;
                int dz = pos.getZ() - cz;
                if (dx * dx + dy * dy + dz * dz > r * r) {
                    continue;
                }
                buffer[count++] = pack(TYPE_HULL, dx, dy, dz);
            }
        }

        // ---- 接触点：被动模式靠的就是这些"声源" ----
        AABB box = new AABB(center).inflate(r);
        for (Entity entity : level.getEntities(exclude, box, e -> e instanceof LivingEntity && e.isAlive())) {
            if (count >= MAX_POINTS) {
                break;
            }
            int dx = Mth.floor(entity.getX()) - cx;
            int dy = Mth.floor(entity.getY()) - cy;
            int dz = Mth.floor(entity.getZ()) - cz;
            if (dx * dx + dy * dy + dz * dz > r * r) {
                continue;
            }
            buffer[count++] = pack(TYPE_CONTACT, dx, dy, dz);
        }

        return new Frame(r, Arrays.copyOf(buffer, count));
    }

    /** 点是否落在以原点到该方向为中心的 30° 圆锥内。 */
    private static boolean inCone(int x, int y, int z, float ux, float uy, float uz) {
        float dot = x * ux + y * uy + z * uz;
        if (dot <= 0.0F) {
            return false;
        }
        float lengthSq = x * x + y * y + z * z;
        return dot * dot >= CONE_COS2 * lengthSq;
    }

    /** 三态网格：不知道（球外 / 未加载）、实心、空。 */
    private static final byte UNKNOWN = 0;
    private static final byte SOLID = 1;
    private static final byte EMPTY = 2;

    private static int index(int i, int j, int k, int n) {
        return (i * n + j) * n + k;
    }

    /** 只有"确定是空的"邻居才算数；网格外和未加载一律是"不知道"，不当成空。 */
    private static boolean knownEmpty(byte[] kind, int i, int j, int k, int n) {
        if (i < 0 || j < 0 || k < 0 || i >= n || j >= n || k >= n) {
            return false;
        }
        return kind[index(i, j, k, n)] == EMPTY;
    }

    /**
     * 打包一个点：类型 2 位 + 三个 9 位坐标（偏移 256，覆盖 −256..255）。
     *
     * <p>原来是 8 位 + 偏移 64（只支持 −64..191），射程一旦超过 64 格，负方向的坐标就会被
     * 截断成垃圾值。放到 9 位之后，160 格射程也能正确表示。</p>
     */
    public static int pack(int type, int dx, int dy, int dz) {
        return ((type & 0x3) << 27)
                | ((dx + 256) & 0x1FF)
                | (((dy + 256) & 0x1FF) << 9)
                | (((dz + 256) & 0x1FF) << 18);
    }

    public static int type(int point) {
        return (point >>> 27) & 0x3;
    }

    public static int x(int point) {
        return (point & 0x1FF) - 256;
    }

    public static int y(int point) {
        return ((point >>> 9) & 0x1FF) - 256;
    }

    public static int z(int point) {
        return ((point >>> 18) & 0x1FF) - 256;
    }
}
