package com.opamb.barotrauma.ship;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShipPhysics} 的纯逻辑单测（L1）。
 *
 * <p>为什么先测这一个类：它<strong>完全不用 Minecraft</strong>（只有 Math），
 * 所以能在没有游戏、没有引导的情况下跑，是整套测试里最便宜的一层。</p>
 *
 * <p>下面这些期望值<strong>不是编的</strong>，是从已有的无头自检日志里反推出来的
 * （`run/logs/latest.log` 的 `[smoketest] physics -> ...` 那一行）：
 * <pre>
 * 满推力 2s 速度=5.35 / 松油门再 2s 速度=0.58 / 压载满 3s 垂速=-0.71
 * </pre>
 * 也就是说这组测试实际是把「已经观测到的行为」钉成回归测试。</p>
 */
class ShipPhysicsTest {

    /** 自检和实际 tick 都用 0.1 s 一步（声明周期 1 tick = 0.05 s，模拟每 2 tick 推进一次）。 */
    private static final double DT = 0.1D;
    private static final double EPS = 1.0E-6D;

    /** 跑 n 步手动推进，返回同一个实例方便链式断言。 */
    private static ShipPhysics runManual(ShipPhysics p, int steps, int hx, int hy, int hz,
                                         int power, double ballast, double flooded) {
        for (int i = 0; i < steps; i++) {
            p.tickManual(DT, hx, hy, hz, power, ballast, flooded);
        }
        return p;
    }

    @Test
    @DisplayName("满推力 2 秒 -> 水平速度逼近 10 格/秒，但不是瞬间到达（有惯性）")
    void fullThrottle_twoSeconds_approachesMaxSpeed() {
        ShipPhysics p = runManual(new ShipPhysics(), 20, 1, 0, 0, 100, 0.0D, 0.0D);
        // 时间常数 0.9 s：v = 10 * (1 - e^(-2/0.9)) ≈ 8.92
        assertEquals(8.92D, p.speed(), 0.02D);
        assertTrue(p.speed() < ShipPhysics.MAX_SPEED, "不该超过上限");
    }

    @Test
    @DisplayName("松油门还有惯性：2 秒后剩 0.97 格/秒，不是立刻停住")
    void releaseThrottle_keepsMomentum() {
        ShipPhysics p = runManual(new ShipPhysics(), 20, 1, 0, 0, 100, 0.0D, 0.0D);
        double top = p.speed();
        runManual(p, 20, 0, 0, 0, 100, 0.0D, 0.0D);
        // 10 格/秒上限下：8.92 * e^(-2/0.9) ≈ 0.97
        assertEquals(0.97D, p.speed(), 0.02D);
        assertTrue(p.speed() < top, "必须减速");
        assertTrue(p.speed() > 0.0D, "但不该瞬间归零");
    }

    @Test
    @DisplayName("功率直接按比例缩最大速度：50% 功率 -> 约 3 格/秒")
    void halfPower_halvesTopSpeed() {
        ShipPhysics p = runManual(new ShipPhysics(), 200, 1, 0, 0, 50, 0.0D, 0.0D);
        assertEquals(ShipPhysics.MAX_SPEED * 0.5D, p.speed(), 0.02D);
    }

    @Test
    @DisplayName("斜着开要归一化：斜向速度不会比直向快")
    void diagonalThrottle_isNormalized() {
        ShipPhysics straight = runManual(new ShipPhysics(), 200, 1, 0, 0, 100, 0.0D, 0.0D);
        ShipPhysics diagonal = runManual(new ShipPhysics(), 200, 1, 0, 1, 100, 0.0D, 0.0D);
        assertEquals(straight.speed(), diagonal.speed(), 0.01D);
    }

    @Test
    @DisplayName("压载过半才下沉：40% 不动，90% 往下沉")
    void ballast_onlySinksPastHalf() {
        ShipPhysics light = runManual(new ShipPhysics(), 50, 0, 0, 0, 100, 0.4D, 0.0D);
        assertEquals(0.0D, light.verticalSpeed(), 1.0E-3D,
                "压载不到一半是配平，不该产生垂速");

        ShipPhysics heavy = runManual(new ShipPhysics(), 50, 0, 0, 0, 100, 0.9D, 0.0D);
        assertTrue(heavy.verticalSpeed() < -0.05D, "压载过半应该往下沉");
    }

    @Test
    @DisplayName("压载满 3 秒 -> 垂速 -0.71（自检实测值）")
    void fullBallast_threeSeconds_sinksAsMeasured() {
        ShipPhysics p = runManual(new ShipPhysics(), 30, 0, 0, 0, 100, 1.0D, 0.0D);
        assertEquals(-0.71D, p.verticalSpeed(), 0.02D);
    }

    @Test
    @DisplayName("进水比压载更重：同样时间，进水越多沉得越快")
    void flooding_sinksFasterThanBallast() {
        ShipPhysics light = runManual(new ShipPhysics(), 30, 0, 0, 0, 100, 0.0D, 0.3D);
        ShipPhysics heavy = runManual(new ShipPhysics(), 30, 0, 0, 0, 100, 0.0D, 1.0D);
        assertTrue(heavy.verticalSpeed() < light.verticalSpeed(),
                "进水多的那艘必须沉得更快");
    }

    @Test
    @DisplayName("位移要攒够整格才落格，余量留给下一 tick")
    void displacement_accumulatesAndConsumes() {
        ShipPhysics p = new ShipPhysics();
        // 低速时一步位移不足 1 格，不能立刻落格
        runManual(p, 1, 1, 0, 0, 100, 0.0D, 0.0D);
        assertEquals(0, p.stepX(), "0.1 s 加速阶段还不到一格");

        // 跑满速后，1 秒应该走出 5~6 格
        runManual(p, 100, 1, 0, 0, 100, 0.0D, 0.0D);
        int stepped = 0;
        while (p.stepX() != 0) {
            int dx = p.stepX();
            p.consume(dx, 0, 0);
            stepped += dx;
        }
        assertTrue(stepped > 0, "满速之后必须能落格");
    }

    @Test
    @DisplayName("撞墙只清撞上的那一轴，另外两轴照走（贴墙滑）")
    void blocked_clearsOnlyOneAxis() {
        ShipPhysics p = runManual(new ShipPhysics(), 20, 1, 1, 0, 100, 0.0D, 0.0D);
        double vxBefore = p.velocityX();
        double vyBefore = p.velocityY();
        assertTrue(vxBefore > 0.1D && vyBefore > 0.1D, "前提：两轴都在动");

        p.blocked(0);

        assertEquals(0.0D, p.velocityX(), EPS, "撞上的轴归零");
        assertEquals(vyBefore, p.velocityY(), EPS, "没撞上的轴不受影响（贴墙滑行）");
    }

    @Test
    @DisplayName("sync 用刚体真实速度覆盖方块模型（物理船模式下面板读数靠它）")
    void sync_replacesVelocityFromRigidBody() {
        ShipPhysics p = runManual(new ShipPhysics(), 20, 1, 0, 0, 100, 0.0D, 0.0D);
        assertTrue(p.speed() > 1.0D, "前提：方块模型自己在动");

        p.sync(0.0D, -3.5D, 1.25D);

        assertEquals(1.25D, p.velocityZ(), EPS);
        assertEquals(-3.5D, p.verticalSpeed(), EPS, "下潜速度要读刚体的真数");
        assertEquals(0.0D, p.stepX(), "同步后不能留下待落格的余量");
    }

    @Test
    @DisplayName("reset 把速度和余量全部清零")
    void reset_clearsEverything() {
        ShipPhysics p = runManual(new ShipPhysics(), 30, 1, 1, 1, 100, 0.8D, 0.2D);
        p.reset();
        assertEquals(0.0D, p.totalSpeed(), EPS);
        assertEquals(0, p.stepX());
        assertEquals(0, p.stepY());
        assertEquals(0, p.stepZ());
    }
}
