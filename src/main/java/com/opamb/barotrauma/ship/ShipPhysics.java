package com.opamb.barotrauma.ship;

/**
 * 船的连续运动模型：速度、加速度、惯性、浮力，最后才量化成方块位移。
 *
 * <p>方块世界没法让方块停在半格上，所以这里把"物理量"和"落格"拆开：
 * 速度和位移都是连续的（格/秒、格）。方块形态的落格移动已删除，
 * 余量留到下一 tick。于是加减速是连续的、转向有惯性、撞墙后不会瞬间停死。</p>
 *
 * <p>数值来源（见 PLAN.md §10.25）：引擎满推力 6 格/秒（1 格 = 1 米，约 21 km/h，
 * 和《潜渊症》里潜艇的巡航速度同量级）；推进器 2000 kW 推这么一坨铁，
 * 从静止到满速要两秒多，所以时间常数取 0.9 s。</p>
 */
public final class ShipPhysics {

    /** 满推力水平速度（格/秒）。方块船路线：提到 10 格/秒，减少"跳格"感。 */
    public static final double MAX_SPEED = 10.0D;
    /** 推进器垂直速度（格/秒）。 */
    public static final double MAX_RISE = 3.5D;
    /** 水平速度逼近时间常数（秒）。越大越"沉"。 */
    private static final double TAU_HORIZONTAL = 0.9D;
    /** 垂直速度逼近时间常数（秒）。水的阻力比空气大得多。 */
    private static final double TAU_VERTICAL = 1.4D;
    /** 压载舱装满水时的下沉速度（格/秒）。压载不到一半不产生漂移，免得半舱水就往下掉。 */
    private static final double BALLAST_SINK = 0.8D;
    /** 舱室进水时的额外下沉速度（格/秒，按进水比例）。 */
    private static final double FLOOD_SINK = 1.5D;

    private double vx;
    private double vy;
    private double vz;

    /** 还没凑够一格的位移余量，保持在 (−1, 1)。 */
    private double fx;
    private double fy;
    private double fz;

    // ------------------------------------------------------------------ 推进

    /** 手动：按八向推力 + 功率设定目标速度，再加上压载/进水带来的下沉。 */
    public void tickManual(double dt, int hx, int hy, int hz, int power, double ballastFilled, double flooded) {
        double throttle = power / 100.0D;
        double horizontal = Math.sqrt((double) hx * hx + (double) hz * hz);
        double tvx = horizontal > 0.0D ? hx / horizontal * MAX_SPEED * throttle : 0.0D;
        double tvz = horizontal > 0.0D ? hz / horizontal * MAX_SPEED * throttle : 0.0D;

        // 压载过半才开始吃重量：半舱水是"配平"，满舱才是"下潜"
        double heavy = Math.max(0.0D, clamp01(ballastFilled) - 0.5D) * 2.0D;
        double sink = -BALLAST_SINK * heavy - FLOOD_SINK * clamp01(flooded);
        double tvy = hy * MAX_RISE * throttle + Math.max(sink, -2.0D);
        approach(dt, tvx, tvy, tvz);
    }

    /** 自动驾驶：直接给目标速度（P 控制，靠近目标时自然减速）。 */
    public void tickAuto(double dt, double tvx, double tvy, double tvz) {
        approach(dt, tvx, tvy, tvz);
    }

    private void approach(double dt, double tvx, double tvy, double tvz) {
        double kh = 1.0D - Math.exp(-dt / TAU_HORIZONTAL);
        double kv = 1.0D - Math.exp(-dt / TAU_VERTICAL);
        vx += (tvx - vx) * kh;
        vz += (tvz - vz) * kh;
        vy += (tvy - vy) * kv;

        fx += vx * dt;
        fy += vy * dt;
        fz += vz * dt;
    }

    private static double clamp01(double value) {
        return value < 0.0D ? 0.0D : Math.min(value, 1.0D);
    }

    // ------------------------------------------------------------------ 落格

    /** 本 tick 攒够的位移（截断取整，负值向下）。 */
    public int stepX() {
        return (int) fx;
    }

    public int stepY() {
        return (int) fy;
    }

    public int stepZ() {
        return (int) fz;
    }

    /** 真的挪过去了才算数：扣掉已经用掉的那部分，余量继续攒。 */
    public void consume(int dx, int dy, int dz) {
        fx -= dx;
        fy -= dy;
        fz -= dz;
    }

    /**
     * 某一轴撞墙：这一轴的速度和余量一起丢掉。
     *
     * <p>只清一轴，另外两轴照走，才有"贴着墙滑"的效果。</p>
     */
    public void blocked(int axis) {
        if (axis == 0) {
            vx = 0.0D;
            fx = 0.0D;
        } else if (axis == 1) {
            vy = 0.0D;
            fy = 0.0D;
        } else {
            vz = 0.0D;
            fz = 0.0D;
        }
    }

    // ------------------------------------------------------------------ 读数

    public double velocityX() {
        return vx;
    }

    public double velocityY() {
        return vy;
    }

    public double velocityZ() {
        return vz;
    }

    /** 某一轴的速度（格/秒）。撞墙判定用：轻轻蹭一下不算撞击。 */
    public double speedOn(int axis) {
        return axis == 0 ? vx : axis == 1 ? vy : vz;
    }

    /** 水平速度（格/秒）。 */
    public double speed() {
        return Math.sqrt(vx * vx + vz * vz);
    }

    /** 垂直速度（格/秒），正数上浮。 */
    public double verticalSpeed() {
        return vy;
    }

    /** 三个方向的总速度（格/秒）。 */
    public double totalSpeed() {
        return Math.sqrt(vx * vx + vy * vy + vz * vz);
    }

    /** 当前水平航向（度，0 = 北，顺时针）——只有真的在动才有意义。 */
    public int bearing() {
        if (Math.abs(vx) + Math.abs(vz) < 1.0E-3D) {
            return -1;
        }
        int deg = (int) Math.round(Math.toDegrees(Math.atan2(vx, -vz)));
        return (deg + 360) % 360;
    }

    /** 停船：速度与余量全部清零（重扫船体时用）。 */
    public void reset() {
        vx = 0.0D;
        vy = 0.0D;
        vz = 0.0D;
        fx = 0.0D;
        fy = 0.0D;
        fz = 0.0D;
    }

    /**
     * 把外部（VS2 刚体）的真实速度写进来。
     *
     * <p>船交给物理引擎之后，方块那套积分不再跑，{@link #speed()} 会一直读 0，
     * 声纳面板上的航速 / 下潜速度就成了假数据。每 tick 用刚体的速度同步一次即可。</p>
     */
    public void sync(double newVx, double newVy, double newVz) {
        vx = newVx;
        vy = newVy;
        vz = newVz;
        fx = 0.0D;
        fy = 0.0D;
        fz = 0.0D;
    }
}
