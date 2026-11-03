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
    /** 船体几何（破口空腔、舱室体积都从这里读）。 */
    private final ShipGeometry.Result geometry;
    /** 连海空腔（破口舱室）里的水量，按 openPockets 的顺序。 */
    private final double[] pocketWater;
    /**
     * 外界进水速率：每个破口空腔每秒进水（格³/秒），随深度略增。
     *
     * <p>2 格³/秒对一间 270 格的舱室要两分多钟，玩家只会以为"没进水"；
     * 20 又太快（一艘船十几秒就沉底）。先从 6 起步：一间 270 格的舱约 45 秒灌满，
     * 给玩家堵漏/排水的时间。以后按真实破口面积算时可以再精调。</p>
     */
    private static final double SEA_INFLOW_PER_SECOND = 6.0D;

    private double temperature = 3000.0D;
    private double fission = 0.5D;
    private double turbine = 0.5D;
    private int fuelRods = 1;
    private double ballast;
    private double ballastCapacity;
    private boolean pumpOut = true;
    /**
     * 破口格数。洞越大进水越快——潜渊症按 Gap 的开口面积算，
     * 我们先按"被砸掉的船体格数"线性放大，至少 1 格。
     */
    private int breachSize = 1;

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
        this.geometry = geometry;
        this.pocketWater = new double[geometry.openPockets().size()];
    }

    /**
     * 破口进水（《潜渊症》模型的 3D 简化版）：连海空腔按"开口面积 × 深度"缓慢灌水。
     *
     * <p>原版公式是 {@code flow = 500 * Size * open² * dt}；我们用每个破口空腔每秒
     * 2 格³ 的基础速率、按深度放大，先保证手感（一两个格子的洞几十秒灌满一间舱）。</p>
     */
    public void tickSeaFlood(double dt, double depthMeters) {
        java.util.List<ShipGeometry.Compartment> pockets = geometry.openPockets();
        for (int i = 0; i < pockets.size(); i++) {
            double capacity = pockets.get(i).volume() * UNITS_PER_BLOCK;
            if (pocketWater[i] >= capacity) {
                continue;
            }
            double depthFactor = 1.0D + Math.max(0.0D, depthMeters) / 100.0D;
            double flow = SEA_INFLOW_PER_SECOND * depthFactor * Math.max(1, breachSize);
            pocketWater[i] = Math.min(capacity,
                    pocketWater[i] + flow * UNITS_PER_BLOCK * dt);
        }
    }

    /**
     * 船体破损后重新分舱：保留反应堆/压载/气闸这些"玩家调过的设置"，
     * 只重建舱室水量数组。否则砸一个洞就会把压载和反应堆输出全部清零。
     */
    public void copySettingsFrom(ShipSimulation old) {
        if (old == null) {
            return;
        }
        this.temperature = old.temperature;
        this.fission = old.fission;
        this.turbine = old.turbine;
        this.fuelRods = old.fuelRods;
        this.ballast = old.ballast;
        this.ballastCapacity = old.ballastCapacity;
        this.pumpOut = old.pumpOut;
        this.airlockPhase = old.airlockPhase;
        this.airlockWater = old.airlockWater;
        this.airlockCycles = old.airlockCycles;
    }

    /**
     * 破损重分舱时把旧水量带进新几何。
     *
     * <p>每砸一个新洞都会重建 `ShipSimulation`；如果水量从 0 开始，
     * 玩家连续砸几格就会看到水位每次归零（"进水效果不对"）。
     * 密闭舱室和连海空腔各按容积比例分配；某一类在新船里不存在时并入另一类。</p>
     */
    public void seedWater(double sealedUnits, double pocketUnits) {
        double sealedCapacity = 0.0D;
        for (int volume : volumes) {
            sealedCapacity += volume * UNITS_PER_BLOCK;
        }
        List<ShipGeometry.Compartment> pockets = geometry.openPockets();
        double pocketCapacity = 0.0D;
        for (ShipGeometry.Compartment pocket : pockets) {
            pocketCapacity += pocket.volume() * UNITS_PER_BLOCK;
        }
        double sealed = Math.max(0.0D, sealedUnits);
        double pocket = Math.max(0.0D, pocketUnits);
        if (pocketCapacity <= 0.0D) {
            sealed += pocket;
            pocket = 0.0D;
        }
        if (sealedCapacity <= 0.0D) {
            pocket += sealed;
            sealed = 0.0D;
        }
        if (sealedCapacity > 0.0D && sealed > 0.0D) {
            double amount = Math.min(sealed, sealedCapacity);
            for (int i = 0; i < water.length; i++) {
                water[i] = amount * (volumes[i] * UNITS_PER_BLOCK / sealedCapacity);
            }
        }
        if (pocketCapacity > 0.0D && pocket > 0.0D) {
            double amount = Math.min(pocket, pocketCapacity);
            for (int i = 0; i < pocketWater.length; i++) {
                pocketWater[i] = amount * (pockets.get(i).volume() * UNITS_PER_BLOCK / pocketCapacity);
            }
        }
    }

    /** 第 index 个连海空腔当前水量（单位）。 */
    public double pocketWater(int index) {
        return index >= 0 && index < pocketWater.length ? pocketWater[index] : 0.0D;
    }

    /** 所有连海空腔的总水量（单位）。 */
    public double pocketWaterTotal() {
        double total = 0.0D;
        for (double value : pocketWater) {
            total += value;
        }
        return total;
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

    /** 设置破口格数（>=1）；洞越多进水越快。 */
    public void setBreachSize(int size) {
        this.breachSize = Math.max(1, size);
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

    /**
     * 往某个密封舱室直接注水（单位）。
     *
     * <p>用于“船壳还没破、但已经裂到渗水”的泄漏：这部分水不经过 openPockets
     * 的海水灌入链路，而是由 {@link ShipState#tickLeaks} 按受损格数量和伤害等级累加。</p>
     */
    public void addWater(int compartmentIndex, double units) {
        if (compartmentIndex < 0 || compartmentIndex >= water.length || units <= 0.0D) {
            return;
        }
        double capacity = volumes[compartmentIndex] * UNITS_PER_BLOCK;
        water[compartmentIndex] = Math.min(capacity, water[compartmentIndex] + units);
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
