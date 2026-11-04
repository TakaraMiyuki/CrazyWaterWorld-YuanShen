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
    /** 独立排水泵方块控制：开关 + 速率（单位/秒，1 格 = 100 单位）。 */
    private boolean drainPumpEnabled;
    private double drainPumpRate = 600.0D;
    /** 排水泵所在格（装配坐标）：只抽这个格子所在的舱。 */
    private net.minecraft.core.BlockPos drainPumpCell;
    /** 格子 → 水体下标的缓存；换几何时整个 simulated 对象会重建，缓存自然失效。 */
    private java.util.Map<net.minecraft.core.BlockPos, Integer> bodyIndexOfCell;
    /** 临时诊断计数。 */
    private int pumpDebugTicks;
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
        this.drainPumpEnabled = old.drainPumpEnabled;
        this.drainPumpRate = old.drainPumpRate;
        this.drainPumpCell = old.drainPumpCell;
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

    /**
     * 开着的门每秒能过多水（单位/秒/格）。
     *
     * <p>原版 {@code Gap.GetWaterFlowFromOutside} 是 {@code 500 * Size * open²}
     * （Size 是门缝像素宽），房间之间的过水量则是 {@code Δ压力 * 300 * (Size/100) * dt}。
     * 换算成我们的单位（1 格 = 100）后，一扇 1×2 的门每秒大约能过几十格³，开门后
     * 水是"涌"进来的，几秒钟就拉平，而不是细水长流。3000 单位/格/秒 = 30 格³/秒/格。</p>
     */
    private static final double DOOR_FLOW_PER_CELL = 3000.0D;

    /** 水位差小于这个值就当已经平了，不再过水（避免无限微流）。 */
    private static final double DOOR_FLOW_DEADBAND = 0.02D;

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

        // 排水泵方块：只抽自己所在的那个舱（原版水泵也是这样——关上的门后面
        // 的水泵不归它管）。旧实现是把全船的水按顺序抽一遍，用户实测"门关着水还能
        // 被泵出去"。水泵位置由方块实体每 tick 报上来（物理船是装配坐标）。
        if (drainPumpEnabled && drainPumpRate > 0.0D) {
            int body = pumpBody();
            if (body >= 0) {
                double take = Math.min(bodyWater(body), drainPumpRate * dt);
                if (take > 0.0D) {
                    removeBodyWater(body, take);
                }
            }
            // 临时诊断：水泵到底抽的是哪个水体，以及它六个邻居分别属于哪个水体。
            if (++pumpDebugTicks % 20 == 0) {
                StringBuilder around = new StringBuilder();
                if (drainPumpCell != null) {
                    net.minecraft.core.BlockPos[] sides = {drainPumpCell.above(),
                            drainPumpCell.below(), drainPumpCell.north(),
                            drainPumpCell.south(), drainPumpCell.east(), drainPumpCell.west()};
                    String[] names = {"上", "下", "北", "南", "东", "西"};
                    for (int i = 0; i < sides.length; i++) {
                        around.append(names[i]).append('=').append(bodyOf(sides[i])).append(' ');
                    }
                }
                com.opamb.barotrauma.BarotraumaMod.LOGGER.info(
                        "[pump] 泵格={} 抽出水体={} 水体水={} 邻居[{}] 密闭总水={} 连海总水={}",
                        drainPumpCell, body, Math.round(bodyWater(Math.max(0, body))),
                        around.toString().trim(), Math.round(waterUnits()),
                        Math.round(pocketWaterTotal()));
            }
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

    // ------------------------------------------------------------------ 门两侧的水

    /**
     * 水体下标（{@link ShipGeometry.DoorLink} 用的那套）：先密闭舱室 0..n-1，
     * 再连海空腔 n..n+m-1。
     */
    public int bodyCount() {
        return water.length + pocketWater.length;
    }

    /** 第 body 个水体的水位（格）。连海空腔按整块水位的近似值算。 */
    public double bodyLevel(int body) {
        List<ShipGeometry.Compartment> sealed = geometry.compartments();
        if (body >= 0 && body < sealed.size()) {
            return ShipWaterStateBuilder.surfaceOf(sealed.get(body), water[body]);
        }
        int pocket = body - sealed.size();
        List<ShipGeometry.Compartment> pockets = geometry.openPockets();
        if (pocket >= 0 && pocket < pockets.size()) {
            return ShipWaterStateBuilder.surfaceOf(pockets.get(pocket), pocketWater[pocket]);
        }
        return 0.0D;
    }

    /** 第 body 个水体的格子数。 */
    public int bodyVolume(int body) {
        List<ShipGeometry.Compartment> sealed = geometry.compartments();
        if (body >= 0 && body < sealed.size()) {
            return sealed.get(body).volume();
        }
        int pocket = body - sealed.size();
        List<ShipGeometry.Compartment> pockets = geometry.openPockets();
        if (pocket >= 0 && pocket < pockets.size()) {
            return pockets.get(pocket).volume();
        }
        return 0;
    }

    /** 第 body 个水体现在的水量（单位）。 */
    public double bodyWater(int body) {
        if (body >= 0 && body < water.length) {
            return water[body];
        }
        int pocket = body - water.length;
        if (pocket >= 0 && pocket < pocketWater.length) {
            return pocketWater[pocket];
        }
        return 0.0D;
    }

    /**
     * 开着的门按限速把高水位一侧的水挪到低水位一侧。
     *
     * <p>这一步是"开门之后水慢慢流过去"，而不是把两个舱并成一个（并舱会让水位
     * 瞬间均摊，关回来时水又整体跑到漏水侧）。关门没有 DoorLink，各自的水原地
     * 不动；开门每秒最多搬 {@link #DOOR_FLOW_PER_CELL} × 门格数，直到水位差进死区。</p>
     */
    public void tickDoorFlow(double dt) {
        List<ShipGeometry.DoorLink> links = geometry.doorLinks();
        if (links.isEmpty() || dt <= 0.0D) {
            return;
        }
        java.util.Map<Integer, Double> levels = new java.util.HashMap<>();
        for (ShipGeometry.DoorLink link : links) {
            if (!link.open() || link.body() == link.other()) {
                continue;
            }
            int a = link.body();
            int b = link.other();
            double la = levels.computeIfAbsent(a, this::bodyLevel);
            double lb = levels.computeIfAbsent(b, this::bodyLevel);
            double diff = la - lb;
            if (Math.abs(diff) <= DOOR_FLOW_DEADBAND) {
                continue;
            }
            boolean aHigh = diff > 0.0D;
            int high = aHigh ? a : b;
            int low = aHigh ? b : a;
            // 想把水位差抹平需要搬的量（按两侧格子数估算）；不够一格时按比例。
            double highCells = Math.max(1, bodyVolume(high));
            double lowCells = Math.max(1, bodyVolume(low));
            double needBlocks = Math.abs(diff) * (highCells * lowCells) / (highCells + lowCells);
            double units = Math.min(needBlocks * UNITS_PER_BLOCK, DOOR_FLOW_PER_CELL * dt);
            units = Math.min(units, bodyWater(high));
            if (units <= 0.05D) {
                continue;
            }
            removeBodyWater(high, units);
            addBodyWater(low, units);
            levels.put(high, bodyLevel(high));
            levels.put(low, bodyLevel(low));
        }
    }

    private void removeBodyWater(int body, double units) {
        if (body >= 0 && body < water.length) {
            water[body] = Math.max(0.0D, water[body] - units);
            return;
        }
        int pocket = body - water.length;
        if (pocket >= 0 && pocket < pocketWater.length) {
            pocketWater[pocket] = Math.max(0.0D, pocketWater[pocket] - units);
        }
    }

    private void addBodyWater(int body, double units) {
        if (body >= 0 && body < water.length) {
            double capacity = volumes[body] * UNITS_PER_BLOCK;
            water[body] = Math.min(capacity, water[body] + units);
            return;
        }
        int pocket = body - water.length;
        List<ShipGeometry.Compartment> pockets = geometry.openPockets();
        if (pocket >= 0 && pocket < pocketWater.length && pocket < pockets.size()) {
            double capacity = pockets.get(pocket).volume() * UNITS_PER_BLOCK;
            pocketWater[pocket] = Math.min(capacity, pocketWater[pocket] + units);
        }
    }

    /**
     * 按"每一格里的水"重建水量。
     *
     * <p>重扫（砸洞、开关门）会重建舱室数组。老的 {@link #seedWater} 只把总水量按
     * 容积比例重新分配，于是：开门（两舱合并）再关门（重新拆开）时，全部水会被
     * 算到"漏水侧"、完好侧被清空。这里改成按格子搬运——每格保留自己那一份，
     * 拆舱/并舱都不会凭空搬水。</p>
     */
    public void seedWaterByCell(java.util.Map<net.minecraft.core.BlockPos, Double> byCell,
                                ShipGeometry.Result next) {
        if (byCell == null || byCell.isEmpty()) {
            return;
        }
        List<ShipGeometry.Compartment> sealed = next.compartments();
        for (int i = 0; i < water.length && i < sealed.size(); i++) {
            double sum = 0.0D;
            for (net.minecraft.core.BlockPos cell : sealed.get(i).cells()) {
                sum += byCell.getOrDefault(cell, 0.0D);
            }
            water[i] = Math.min(volumes[i] * UNITS_PER_BLOCK, sum);
        }
        List<ShipGeometry.Compartment> pockets = next.openPockets();
        for (int i = 0; i < pocketWater.length && i < pockets.size(); i++) {
            double sum = 0.0D;
            for (net.minecraft.core.BlockPos cell : pockets.get(i).cells()) {
                sum += byCell.getOrDefault(cell, 0.0D);
            }
            pocketWater[i] = Math.min(pockets.get(i).volume() * UNITS_PER_BLOCK, sum);
        }
    }

    public void setPumpOut(boolean pumpOut) {
        this.pumpOut = pumpOut;
    }

    /** 排水泵设置：速率夹紧到 100..3000 单位/秒（1~30 格³/秒）。 */
    public void setDrainPump(boolean enabled, double rate) {
        this.drainPumpEnabled = enabled;
        this.drainPumpRate = Math.max(100.0D, Math.min(3000.0D, rate));
    }

    /** 排水泵的位置（装配坐标）：只抽这一格所在的舱。 */
    public void setDrainPumpCell(net.minecraft.core.BlockPos cell) {
        this.drainPumpCell = cell == null ? null : cell.immutable();
    }

    /** 排水泵所在的那个水体：泵本身是实心方块，取它旁边那一格舱室。 */
    private int pumpBody() {
        int body = bodyOf(drainPumpCell);
        if (body >= 0 || drainPumpCell == null) {
            return body;
        }
        for (net.minecraft.core.BlockPos next : new net.minecraft.core.BlockPos[]{
                drainPumpCell.above(), drainPumpCell.below(), drainPumpCell.north(),
                drainPumpCell.south(), drainPumpCell.east(), drainPumpCell.west()}) {
            body = bodyOf(next);
            if (body >= 0) {
                return body;
            }
        }
        return -1;
    }

    /** 这一格在第几个水体里（0..n-1 密闭舱室，n.. 连海空腔）；不在任何舱里返回 -1。 */
    public int bodyOf(net.minecraft.core.BlockPos cell) {
        if (cell == null) {
            return -1;
        }
        if (bodyIndexOfCell == null) {
            bodyIndexOfCell = new java.util.HashMap<>();
            List<ShipGeometry.Compartment> sealed = geometry.compartments();
            for (int i = 0; i < sealed.size(); i++) {
                for (net.minecraft.core.BlockPos pos : sealed.get(i).cells()) {
                    bodyIndexOfCell.putIfAbsent(pos, i);
                }
            }
            List<ShipGeometry.Compartment> pockets = geometry.openPockets();
            for (int j = 0; j < pockets.size(); j++) {
                for (net.minecraft.core.BlockPos pos : pockets.get(j).cells()) {
                    bodyIndexOfCell.putIfAbsent(pos, sealed.size() + j);
                }
            }
        }
        return bodyIndexOfCell.getOrDefault(cell, -1);
    }

    public ShipGeometry.Result geometry() {
        return geometry;
    }

    public boolean drainPumpEnabled() {
        return drainPumpEnabled;
    }

    public double drainPumpRate() {
        return drainPumpRate;
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
