package com.opamb.barotrauma.world;

/**
 * 深海地形用的确定性噪声。
 *
 * <p>不用 {@code RandomSource} 逐点取随机，而是用整数哈希 + 平滑插值的 value noise：
 * 同一个世界种子、同一坐标永远得到同一个值，跨区块天然连续，且不需要线程同步。</p>
 */
public final class DeepSeaNoise {

    private DeepSeaNoise() {
    }

    /** 3D 哈希 → [-1, 1]。 */
    private static double hash(long seed, int x, int y, int z) {
        long h = seed;
        h ^= x * 0x9E3779B97F4A7C15L;
        h ^= y * 0xC2B2AE3D27D4EB4FL;
        h ^= z * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return ((h >>> 11) & 0x1FFFFFFFFFFFFFL) / (double) (1L << 53) * 2.0 - 1.0;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    /** 3D value noise，输出 [-1, 1]。 */
    public static double noise3(long seed, double x, double y, double z) {
        int xi = floor(x);
        int yi = floor(y);
        int zi = floor(z);
        double tx = fade(x - xi);
        double ty = fade(y - yi);
        double tz = fade(z - zi);

        double c000 = hash(seed, xi, yi, zi);
        double c100 = hash(seed, xi + 1, yi, zi);
        double c010 = hash(seed, xi, yi + 1, zi);
        double c110 = hash(seed, xi + 1, yi + 1, zi);
        double c001 = hash(seed, xi, yi, zi + 1);
        double c101 = hash(seed, xi + 1, yi, zi + 1);
        double c011 = hash(seed, xi, yi + 1, zi + 1);
        double c111 = hash(seed, xi + 1, yi + 1, zi + 1);

        double x00 = lerp(c000, c100, tx);
        double x10 = lerp(c010, c110, tx);
        double x01 = lerp(c001, c101, tx);
        double x11 = lerp(c011, c111, tx);

        return lerp(lerp(x00, x10, ty), lerp(x01, x11, ty), tz);
    }

    /** 2D value noise，输出 [-1, 1]。 */
    public static double noise2(long seed, double x, double z) {
        return noise3(seed, x, 0.0, z);
    }

    /** 分形叠加，输出 [-1, 1]。 */
    public static double fbm3(long seed, double x, double y, double z, int octaves) {
        double sum = 0.0;
        double amp = 1.0;
        double norm = 0.0;
        double freq = 1.0;
        for (int i = 0; i < octaves; i++) {
            sum += amp * noise3(seed + i * 7919L, x * freq, y * freq, z * freq);
            norm += amp;
            amp *= 0.5;
            freq *= 2.0;
        }
        return sum / norm;
    }

    public static double fbm2(long seed, double x, double z, int octaves) {
        return fbm3(seed, x, 0.0, z, octaves);
    }
}
