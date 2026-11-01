package com.opamb.barotrauma.ship;

import net.minecraft.util.Mth;

import java.util.List;

/**
 * 船网数值模拟（M3.A3）。
 *
 * <p>单位约定：1 格舱容 = 100 单位（水/氧气都用这个换算），这样 600/s 的水泵抽 273 格的舱室
 * 大约 45 秒，和《潜渊症》的手感接近。所有数值都来自 wiki：</p>
 *
 * <ul>
 *   <li>反应堆：最大 5 000 kW；温度阈值 过热 6482 / 熔毁 7965；燃料棒热势 80</li>
 *   <li>制氧机：700 氧/s 供养 1 人；每人 700/s 消耗</li>
 *   <li>水泵：300 kW，最大 600/s；压载舱容量 600/个</li>
 *   <li>引擎：2 000 kW，力 300</li>
 * </ul>
 *
 * <p>固定步长由调用方给（命令里用 0.1 s），与帧率无关。</p>
 */
public final class ShipSimulation {

    public static final double UNITS_PER_BLOCK = 100.0D;

    private static final double REACTOR_MAX_KW = 5000.0D;
    private static final double O2_PER_GENERATOR = 700.0D;
    private static final double CREW_OXYGEN = 700.0D;
    private static final double PUMP_RATE = 600.0D;
    private static final double PUMP_POWER = 300.0D;
    private static final double O2_POWER = 300.0D;
    /** 声纳台耗电：wiki 的 Navigation Terminal / Sonar Monitor 都是 100。 */
    private static final double SONAR_POWER = 100.0D;
    /** 驾驶台（Nav Terminal 本体）也吃 100；我们把它和声纳合在一台机器上，所以只算一份。 */
    private static final double ENGINE_POWER = 2000.0D;
    private static final double BALLAST_PER_TANK = 600.0D;

    private static final double OVERHEAT = 6482.0D;
    private static final double MELTDOWN = 7965.0D;

    private final int[] volumes;
    private final double[] water;
    private final double[] oxygen;
    private final MachineCount machines;

    private double temperature = 3000.0D;
    private double fission = 0.5D;
    private double turbine = 0.5D;
    private int fuelRods = 1;
    private double ballast;
    private double ballastCapacity;
    private boolean pumpOut = true;

    private double lastOutput;
    private double lastDemand;
    private double lastRatio = 1.0D;

    /** 气闸舱室容积（27 格 = 2700 单位）。 */
    private static final double AIRLOCK_VOLUME = 2700.0D;
    private static final double AIRLOCK_POWER = 300.0D;
    private static final double AIRLOCK_RATE = 600.0D;

    /** 气闸循环阶段。 */
    public enum AirlockPhase {
        CLOSED, DRAINING, FLOODING
    }

    private AirlockPhase airlockPhase = AirlockPhase.CLOSED;
    private double airlockWater = AIRLOCK_VOLUME;
    private int airlockCycles;

    public ShipSimulation(ShipGeometry.Result geometry, MachineCount machines) {
        List<ShipGeometry.Compartment> compartments = geometry.compartments();
        int size = compartments.size();
        volumes = new int[size];
        water = new double[size];
        oxygen = new double[size];
        for (int i = 0; i < size; i++) {
            ShipGeometry.Compartment compartment = compartments.get(i);
            volumes[i] = compartment.volume();
            water[i] = compartment.waterBlocks() * UNITS_PER_BLOCK;
            oxygen[i] = compartments.get(i).volume() * UNITS_PER_BLOCK;
        }
        this.machines = machines;
        this.ballastCapacity = machines.ballast() * BALLAST_PER_TANK;
    }

    /** 推进一个时间步（秒）。 */
    public void tick(double dt) {
        double output = machines.reactor() * REACTOR_MAX_KW * turbine;
        double demand = machines.pump() * PUMP_POWER
                + machines.oxygen() * O2_POWER
                + machines.sonar() * SONAR_POWER
                + (airlockPhase == AirlockPhase.CLOSED ? 0.0D : AIRLOCK_POWER)
                + machines.engine() * ENGINE_POWER * 0.5D;
        double ratio = demand <= 0.0D ? 1.0D : Math.min(1.0D, output / demand);
        lastOutput = output;
        lastDemand = demand;
        lastRatio = ratio;

        // 温度：裂变产热 - 涡轮带走的热 - 自然散热
        double heatIn = fission * 80.0D * fuelRods * 2.0D * machines.reactor();
        double heatOut = turbine * REACTOR_MAX_KW * 0.02D * machines.reactor();
        temperature += (heatIn - heatOut) * dt;
        temperature -= Math.max(0.0D, temperature - 2000.0D) * 0.0015D * dt;
        temperature = Mth.clamp(temperature, 0.0D, 12000.0D);

        // 制氧：按舱室体积分配
        int totalVolume = 0;
        for (int volume : volumes) {
            totalVolume += volume;
        }
        if (totalVolume > 0 && machines.oxygen() > 0) {
            double produced = machines.oxygen() * O2_PER_GENERATOR * ratio * dt;
            for (int i = 0; i < oxygen.length; i++) {
                oxygen[i] += produced * volumes[i] / (double) totalVolume;
            }
        }
        // 乘员消耗（一个人，从最大的舱室扣——M3 先不追踪玩家位置）
        int largest = -1;
        for (int i = 0; i < volumes.length; i++) {
            if (largest < 0 || volumes[i] > volumes[largest]) {
                largest = i;
            }
        }
        if (largest >= 0) {
            oxygen[largest] = Math.max(0.0D, oxygen[largest] - CREW_OXYGEN * dt);
        }

        // 水泵：抽舱室的水，或者往压载舱注水
        double moved = machines.pump() * PUMP_RATE * ratio * dt;
        if (pumpOut) {
            for (int i = 0; i < water.length && moved > 0.0D; i++) {
                double take = Math.min(water[i], moved);
                water[i] -= take;
                moved -= take;
            }
        } else {
            double add = Math.min(moved, ballastCapacity - ballast);
            ballast += Math.max(0.0D, add);
        }

        // 气闸：抽干 / 注水，完成后自动回到 CLOSED（此时另一侧门才允许开）
        if (airlockPhase == AirlockPhase.DRAINING) {
            airlockWater = Math.max(0.0D, airlockWater - AIRLOCK_RATE * ratio * dt);
            if (airlockWater <= 0.0D) {
                airlockPhase = AirlockPhase.CLOSED;
                airlockCycles++;
            }
        } else if (airlockPhase == AirlockPhase.FLOODING) {
            airlockWater = Math.min(AIRLOCK_VOLUME, airlockWater + AIRLOCK_RATE * ratio * dt);
            if (airlockWater >= AIRLOCK_VOLUME) {
                airlockPhase = AirlockPhase.CLOSED;
                airlockCycles++;
            }
        }
    }

    /** 请求抽干气闸（出舱）。没有气闸或已经是空的返回 false。 */
    public boolean requestDrain() {
        if (machines.airlock() <= 0 || airlockPhase != AirlockPhase.CLOSED || airlockWater <= 0.0D) {
            return false;
        }
        airlockPhase = AirlockPhase.DRAINING;
        return true;
    }

    /** 请求给气闸注水（回船）。没有气闸或已经满了返回 false。 */
    public boolean requestFlood() {
        if (machines.airlock() <= 0 || airlockPhase != AirlockPhase.CLOSED
                || airlockWater >= AIRLOCK_VOLUME) {
            return false;
        }
        airlockPhase = AirlockPhase.FLOODING;
        return true;
    }

    public AirlockPhase airlockPhase() {
        return airlockPhase;
    }

    public double airlockWater() {
        return airlockWater;
    }

    public int airlockCycles() {
        return airlockCycles;
    }

    /** 直接往第 index 个舱室灌水（测试/事件用）。 */
    public void flood(int index, double units) {
        if (index >= 0 && index < water.length) {
            water[index] += units;
        }
    }

    public void setPumpOut(boolean pumpOut) {
        this.pumpOut = pumpOut;
    }

    /** 设置裂变率 / 涡轮输出（0..1）。 */
    public void setFission(double fission) {
        this.fission = Mth.clamp(fission, 0.0D, 1.0D);
    }

    public void setTurbine(double turbine) {
        this.turbine = Mth.clamp(turbine, 0.0D, 1.0D);
    }

    public double fission() {
        return fission;
    }

    public double turbine() {
        return turbine;
    }

    public boolean pumpOut() {
        return pumpOut;
    }

    public double waterUnits() {
        double sum = 0.0D;
        for (double value : water) {
            sum += value;
        }
        return sum;
    }

    /** 第 index 个舱室的水量（单位）。 */
    public double waterUnits(int index) {
        return index >= 0 && index < water.length ? water[index] : 0.0D;
    }

    /** 第 index 个舱室的容积（格）。 */
    public int compartmentVolume(int index) {
        return index >= 0 && index < volumes.length ? volumes[index] : 0;
    }

    public double oxygenPercent() {
        double total = 0.0D;
        double totalVolume = 0.0D;
        for (int i = 0; i < oxygen.length; i++) {
            total += oxygen[i];
            totalVolume += volumes[i] * UNITS_PER_BLOCK;
        }
        return totalVolume <= 0.0D ? 0.0D : total / totalVolume;
    }

    public double ballastPercent() {
        return ballastCapacity <= 0.0D ? 0.0D : ballast / ballastCapacity;
    }

    public Report report() {
        return new Report(temperature, lastOutput, lastDemand, lastRatio,
                oxygenPercent(), waterUnits(), ballastPercent(), ballast, ballastCapacity,
                airlockPhase, airlockWater / AIRLOCK_VOLUME, airlockCycles,
                temperature >= OVERHEAT, temperature >= MELTDOWN);
    }

    /** 一次模拟快照。 */
    public record Report(double temperature, double powerOutput, double powerDemand, double powerRatio,
                         double oxygenPercent, double waterUnits, double ballastPercent,
                         double ballastUnits, double ballastCapacity,
                         AirlockPhase airlockPhase, double airlockPercent, int airlockCycles,
                         boolean overheated, boolean meltingDown) {
    }
}
