package com.opamb.barotrauma.ship.vs;

import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.ships.PhysShip;
import org.valkyrienskies.core.api.ships.ShipPhysicsListener;
import org.valkyrienskies.core.api.world.PhysLevel;

import java.util.Locale;

/**
 * 挂在 VS2 船上的推进器 + 姿态稳定器。
 *
 * <p>VS2 每个物理步都会回调 {@link #physTick}，这里把"油门 / 功率 / 压载"换算成力施加给刚体。
 * 之所以在物理回调里算而不是每 server tick 算一次：物理步比 tick 密，力是连续给的，船才不会一顿一顿。</p>
 *
 * <p>推力按<strong>加速度</strong>给（力 = 质量 × 加速度 × 油门），所以不需要预先知道船有多重；
 * 质量是 VS2 按方块算出来的，改船体之后自动生效。</p>
 *
 * <h2>姿态：永远平行于海平面</h2>
 *
 * <p>潜艇不能像木筏一样翻。仿真里没有任何力矩来源（推力都作用在质心上），
 * 倾斜只可能来自碰撞，所以这里做两件事：</p>
 * <ol>
 *   <li><b>角速度阻尼</b>：每个物理步施加一个和角速度反向的力矩，把"转起来"这件事掐死在源头；</li>
 *   <li><b>回正力矩</b>：船真被撞歪了，按倾角给一个把"船的上方向"拉回世界 +Y 的力矩。</li>
 * </ol>
 * <p>两个力矩都乘了质量——转动惯量本身随质量平方级增长，不按质量缩放的话，
 * 大船纹丝不动、小船被甩飞。正常的《潜渊症》潜艇也是靠压载舱自动配平保持水平的。</p>
 */
public final class VsThruster implements ShipPhysicsListener {

    /** 满油门时的水平加速度（格/秒²）。 */
    private static final double ACCEL = 2.5D;
    /** 垂直推进器加速度（格/秒²）。 */
    private static final double ACCEL_VERTICAL = 1.8D;
    /** 压载满水时额外的下沉比例（相对重力加速度）。 */
    private static final double BALLAST_WEIGHT = 0.35D;
    /** 进水满舱时损失的浮力比例；剩下的 0.35 是船体结构本身的残余浮力。 */
    private static final double FLOOD_WEIGHT = 0.65D;
    /** 进水后给垂直速度的阻尼，避免满舱后像石头一样无限加速。 */
    private static final double FLOOD_DRAG = 0.65D;
    /** 维度重力，和 data/valkyrienskies/vs_dimension_parameters/the_deep.json 里的 -10 对应。 */
    private static final double GRAVITY = 10.0D;
    /** 无垂直输入时阻尼残余垂速（1/秒）。 */
    private static final double VERTICAL_DAMPING = 2.0D;
    /** 无垂直输入时把船拉回"松开按键那一刻的深度"（1/秒²/格）。 */
    private static final double DEPTH_HOLD_GAIN = 0.8D;

    /** 角速度阻尼（乘质量；越大越"锁死"在水平）。 */
    private static final double ATTITUDE_DAMPING = 900.0D;
    /** 姿态回正增益（乘质量；每弧度倾角给多少力矩）。 */
    private static final double ATTITUDE_GAIN = 700.0D;
    /** 力矩上限（乘质量），防止极端情况下把船甩飞。 */
    private static final double ATTITUDE_TORQUE_CAP = 4000.0D;

    private static double clamp01(double value) {
        return value < 0.0D ? 0.0D : Math.min(value, 1.0D);
    }

    private volatile int throttleX;
    private volatile int throttleY;
    private volatile int throttleZ;
    private volatile int power = 100;
    private volatile double ballast;
    private volatile double flooded;
    /** 没有垂直输入时要保持的深度（NaN = 还没记录 / 正在手动下潜）。 */
    private double holdY = Double.NaN;

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
    private static volatile double probeForceX;
    private static volatile double probeForceZ;
    private static volatile double probeX;
    private static volatile double probeY;
    private static volatile double probeZ;
    /** 物理体自己的速度——判断"力到底进没进积分器"的关键。 */
    private static volatile double probeVelX;
    private static volatile double probeVelY;
    private static volatile double probeVelZ;
    private static volatile boolean probeSleeping;
    /** 回调给的 PhysShip 和 level.getShipById() 是不是同一个对象。 */
    private static volatile String probeLive = "";
    /** 姿态：倾角（度）和船头朝向的世界偏航（度）。 */
    private static volatile double probeTiltDegrees;
    private static volatile double probeYawDegrees;

    public static String probe() {
        return String.format(Locale.ROOT,
                "static=%s sleeping=%s live=%s mass=%.0f liquid=%.3f 力=(%.0f,%.0f,%.0f) 物理体pos=(%.1f,%.1f,%.1f) vel=(%.3f,%.3f,%.3f)",
                probeStatic, probeSleeping, probeLive, probeMass, probeLiquid,
                probeForceX, probeForceY, probeForceZ,
                probeX, probeY, probeZ, probeVelX, probeVelY, probeVelZ);
    }

    /** 姿态摘要：倾角越小越好，理想是 0.0°。 */
    public static String attitude() {
        return String.format(Locale.ROOT, "倾角 %.1f° 偏航 %.1f°", probeTiltDegrees, probeYawDegrees);
    }

    /** 当前倾角（度）。0 = 完全平行于海平面；GameTest 用它断言"不会翻"。 */
    public static double tiltDegrees() {
        return probeTiltDegrees;
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

    /** 唤醒次数：诊断"力没进积分器"到底是休眠还是别的。 */
    private static final java.util.concurrent.atomic.AtomicLong WAKES =
            new java.util.concurrent.atomic.AtomicLong();

    public static long wakes() {
        return WAKES.get();
    }

    @Override
    public void physTick(PhysShip ship, PhysLevel level) {
        CALLS.incrementAndGet();
        // VS2 会把静止的船标成 static / 让它进休眠；之后 applyInvariantForce 不再进积分器
        // （实测：力每个物理步都在给 325 kN，速度却恒定 0、位置一个格都不动）。
        // 只要油门不是零，就保证这艘船是动态的。
        if ((ship.isStatic() || ship.isSleeping())
                && (throttleX != 0 || throttleY != 0 || throttleZ != 0)) {
            ship.setStatic(false);
            WAKES.incrementAndGet();
        }
        double mass = ship.getMass();
        // 回调拿到的 ship 可能只是一份"快照"，物理世界里的真身要用 level.getShipById 取。
        // 如果两者不是同一个对象，力施加在快照上就会全部丢失——这正是"力一直在算、
        // 速度恒为 0"的另一种可能原因，直接探出来。
        org.valkyrienskies.core.api.ships.PhysShip live = null;
        try {
            live = level.getShipById(ship.getId());
            probeLive = live == null ? "无" : (live == ship ? "同一个" : "不同对象");
        } catch (Throwable error) {
            probeLive = "查询异常";
        }
        probeStatic = ship.isStatic();
        probeMass = mass;
        probeLiquid = ship.getLiquidOverlap();
        probeSleeping = ship.isSleeping();
        org.joml.Vector3dc where = ship.getTransform().getPositionInWorld();
        probeX = where.x();
        probeY = where.y();
        probeZ = where.z();
        org.joml.Vector3dc vel = ship.getVelocity();
        probeVelX = vel.x();
        probeVelY = vel.y();
        probeVelZ = vel.z();

        // 优先施加到 level.getShipById 取回来的那个对象上：如果回调给的 ship 只是
        // 一份快照，力压在快照上就全丢了。拿不到才退回回调给的对象。
        org.valkyrienskies.core.api.ships.PhysShip target = live != null ? live : ship;

        stabilize(target, mass);

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
        // 空气海域的浮力完全由数值计算：完整船体接近中性，压载/进水按比例扣掉浮力。
        // 不再依赖 VS2 的 liquidOverlap（它对我们的自定义方块恒为 0），也不依赖世界里的水。
        double ballastLoad = clamp01(ballast);
        double floodLoad = clamp01(flooded);
        ay += GRAVITY * (1.0D - BALLAST_WEIGHT * ballastLoad - FLOOD_WEIGHT * floodLoad);
        if (floodLoad > 0.02D) {
            // 满舱时给一个垂直阻尼，避免下沉速度无限增大；也保留一点“水阻”的手感。
            ay += -vel.y() * FLOOD_DRAG;
        }

        // 没有垂直输入时保持深度：VS2 的实际重力和我们补的 GRAVITY 不一定严格相等，
        // 不锁的话船会一直缓慢上浮（用户实测"物理船一直往上浮"）。这里用
        // "垂速阻尼 + 深度误差回拉"做深度保持；有油门/压载/进水时让开。
        boolean verticalInput = throttleY != 0 || clamp01(ballast) > 0.02D || clamp01(flooded) > 0.02D;
        if (verticalInput) {
            holdY = Double.NaN;
        } else {
            if (Double.isNaN(holdY)) {
                holdY = where.y();
            }
            ay += -vel.y() * VERTICAL_DAMPING + (holdY - where.y()) * DEPTH_HOLD_GAIN;
        }

        double fx = ax * mass;
        double fy = ay * mass;
        double fz = az * mass;
        lastForceX = fx;
        lastForceY = fy;
        lastForceZ = fz;
        probeForceY = fy;
        probeForceX = fx;
        probeForceZ = fz;
        if (fx != 0.0D || fy != 0.0D || fz != 0.0D) {
            // 用 applyWorldForce(力, 世界坐标点)，**不要用 applyInvariantForce**。
            // 反编译 core 的 PhysShipImpl 看得很清楚：
            //   applyInvariantForce(f) → applyWorldForceToBodyPos(f, null)
            //     → invPosPositions.add(null); invPosForces.add(f)
            // 然后 applyQueuedForces() 里执行 rigidBodyReference.a(null, force)——
            // 位置是 null，力到底有没有落到刚体上完全不可控。这就是"力一直在算、
            // 速度恒为 0"最像的成因。
            // applyWorldForce 会先把世界坐标变换到模型坐标、再减质心，得到一个真实的
            // 体坐标点，走的是同一条队列但没有 null。
            org.joml.Vector3dc comModel = ship.getCenterOfMass();
            Vector3d comWorld = ship.getTransform().getShipToWorld()
                    .transformPosition(new Vector3d(comModel), new Vector3d());
            target.applyWorldForce(new Vector3d(fx, fy, fz), comWorld);
            DIRECT_CALLS.incrementAndGet();
        }
    }

    /**
     * 姿态稳定：把船的"上方向"锁在世界 +Y，并阻尼一切转动。
     *
     * <p>推力本身不产生力矩（都作用在质心），所以只要把转动掐死，
     * 船就永远平行于海平面，不会翻。</p>
     */
    private static void stabilize(PhysShip ship, double mass) {
        try {
            if (ship.isStatic() || mass <= 0.0D) {
                return;
            }
            Vector3dc angular = ship.getAngularVelocity();
            Quaterniondc rotation = ship.getTransform().getShipToWorldRotation();
            // 船自己的上方向，转成世界方向
            Vector3d up = rotation.transform(new Vector3d(0.0D, 1.0D, 0.0D), new Vector3d());
            double cosTilt = Math.max(-1.0D, Math.min(1.0D, up.y));
            double tilt = Math.acos(cosTilt);
            probeTiltDegrees = Math.toDegrees(tilt);
            // 船头方向 → 偏航角（纯诊断用）
            Vector3d forward = rotation.transform(new Vector3d(0.0D, 0.0D, 1.0D), new Vector3d());
            probeYawDegrees = Math.toDegrees(Math.atan2(forward.x, forward.z));

            if (tilt < 1.0E-5D && angular.lengthSquared() < 1.0E-9D) {
                return;
            }
            // 1) 角速度阻尼：和角速度反向
            Vector3d torque = new Vector3d(-angular.x(), -angular.y(), -angular.z())
                    .mul(ATTITUDE_DAMPING * mass);
            // 2) 回正：转轴 = 上方向 × 世界 +Y，绕它正转就能把 up 转回 +Y
            if (tilt > 1.0E-4D) {
                Vector3d axis = up.cross(new Vector3d(0.0D, 1.0D, 0.0D), new Vector3d());
                double axisLength = axis.length();
                if (axisLength > 1.0E-6D) {
                    axis.div(axisLength);
                    torque.fma(tilt * ATTITUDE_GAIN * mass, axis);
                } else if (cosTilt < 0.0D) {
                    // 正好完全翻过来：转轴退化了，随便挑一个水平轴把它推回去
                    torque.fma(tilt * ATTITUDE_GAIN * mass, new Vector3d(1.0D, 0.0D, 0.0D));
                }
            }
            // 力矩封顶，别把船甩飞
            double cap = ATTITUDE_TORQUE_CAP * mass;
            double magnitude = torque.length();
            if (magnitude > cap) {
                torque.mul(cap / magnitude);
            }
            if (torque.lengthSquared() > 0.0D) {
                ship.applyInvariantTorque(torque);
            }
        } catch (Throwable ignored) {
            // 姿态锁失败不影响推力
        }
    }

    /** 直接压在 PhysShip 上的次数（诊断用）。 */
    private static final java.util.concurrent.atomic.AtomicLong DIRECT_CALLS =
            new java.util.concurrent.atomic.AtomicLong();

    public static long directCalls() {
        return DIRECT_CALLS.get();
    }
}
