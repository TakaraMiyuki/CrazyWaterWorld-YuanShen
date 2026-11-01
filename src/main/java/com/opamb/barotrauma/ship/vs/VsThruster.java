package com.opamb.barotrauma.ship.vs;

import org.joml.Vector3d;
import org.valkyrienskies.core.api.ships.PhysShip;
import org.valkyrienskies.core.api.ships.ShipPhysicsListener;
import org.valkyrienskies.core.api.world.PhysLevel;

/**
 * 挂在 VS2 船上的推进器。
 *
 * <p>VS2 每个物理步都会回调 {@link #physTick}，这里把"油门 / 功率 / 压载"换算成力施加给刚体。
 * 之所以在物理回调里算而不是每 server tick 算一次：物理步比 tick 密，力是连续给的，船才不会一顿一顿。</p>
 *
 * <p>推力按<strong>加速度</strong>给（力 = 质量 × 加速度 × 油门），所以不需要预先知道船有多重；
 * 质量是 VS2 按方块算出来的，改船体之后自动生效。</p>
 */
public final class VsThruster implements ShipPhysicsListener {

    /** 满油门时的水平加速度（格/秒²）。 */
    private static final double ACCEL = 2.5D;
    /** 垂直推进器加速度（格/秒²）。 */
    private static final double ACCEL_VERTICAL = 1.8D;
    /** 压载满水时额外的下沉比例（相对重力加速度）。 */
    private static final double BALLAST_WEIGHT = 0.35D;
    /** 维度重力，和 data/valkyrienskies/vs_dimension_parameters/the_deep.json 里的 -10 对应。 */
    private static final double GRAVITY = 10.0D;

    private static double clamp01(double value) {
        return value < 0.0D ? 0.0D : Math.min(value, 1.0D);
    }

    private volatile int throttleX;
    private volatile int throttleY;
    private volatile int throttleZ;
    private volatile int power = 100;
    private volatile double ballast;
    private volatile double flooded;

    // 诊断用：上一次物理步实际施加的力（牛顿）
    private volatile double lastForceX;
    private volatile double lastForceY;
    private volatile double lastForceZ;

    /** 物理回调被调用的次数。用来判断"力没生效"到底是没回调还是回调了没用。 */
    private static final java.util.concurrent.atomic.AtomicLong CALLS =
            new java.util.concurrent.atomic.AtomicLong();

    public static long calls() {
        return CALLS.get();
    }

    // 物理回调里看到的最新状态：判断"船不动/穿模"到底是静态船、还是浮力/碰撞没算
    private static volatile boolean probeStatic;
    private static volatile double probeMass;
    private static volatile double probeLiquid;
    private static volatile double probeForceY;
    private static volatile double probeX;
    private static volatile double probeY;
    private static volatile double probeZ;

    public static String probe() {
        return String.format(java.util.Locale.ROOT,
                "static=%s mass=%.0f liquidOverlap=%.3f 力Y=%.0f 物理体位置=(%.1f,%.1f,%.1f)",
                probeStatic, probeMass, probeLiquid, probeForceY, probeX, probeY, probeZ);
    }

    public void setThrottle(int x, int y, int z) {
        throttleX = x;
        throttleY = y;
        throttleZ = z;
    }

    public int throttleX() {
        return throttleX;
    }

    public int throttleY() {
        return throttleY;
    }

    public int throttleZ() {
        return throttleZ;
    }

    public void setPower(int percent) {
        power = percent < 5 ? 5 : Math.min(percent, 100);
    }

    public int power() {
        return power;
    }

    public void setBallast(double filled, double flood) {
        ballast = filled;
        flooded = flood;
    }

    public double lastForceX() {
        return lastForceX;
    }

    public double lastForceY() {
        return lastForceY;
    }

    public double lastForceZ() {
        return lastForceZ;
    }

    @Override
    public void physTick(PhysShip ship, PhysLevel level) {
        CALLS.incrementAndGet();
        double mass = ship.getMass();
        probeStatic = ship.isStatic();
        probeMass = mass;
        probeLiquid = ship.getLiquidOverlap();
        org.joml.Vector3dc where = ship.getTransform().getPositionInWorld();
        probeX = where.x();
        probeY = where.y();
        probeZ = where.z();
        if (mass <= 0.0D) {
            return;
        }
        double throttle = power / 100.0D;

        // 水平：八向推力归一化，斜着走不会比直着走快
        double hx = throttleX;
        double hz = throttleZ;
        double len = Math.sqrt(hx * hx + hz * hz);
        double ax = 0.0D;
        double az = 0.0D;
        if (len > 0.0D) {
            ax = hx / len * ACCEL * throttle;
            az = hz / len * ACCEL * throttle;
        }
        double ay = throttleY * ACCEL_VERTICAL * throttle;
        // 自己补浮力：VS2 对我们的自定义方块算不出排水量（实测 liquidOverlap 恒为 0），
        // 不补的话船就是一块石头，直接沉底穿模。这里按"完全中性"补偿重力，
        // 压载/进水再让它变重往下沉。
        ay += GRAVITY * (1.0D - 0.5D * (clamp01(ballast) + clamp01(flooded)));

        double fx = ax * mass;
        double fy = ay * mass;
        double fz = az * mass;
        lastForceX = fx;
        lastForceY = fy;
        lastForceZ = fz;
        probeForceY = fy;
        if (fx != 0.0D || fy != 0.0D || fz != 0.0D) {
            ship.applyInvariantForce(new Vector3d(fx, fy, fz));
        }
    }
}
