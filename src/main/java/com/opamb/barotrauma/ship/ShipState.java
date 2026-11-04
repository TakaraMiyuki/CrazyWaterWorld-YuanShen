package com.opamb.barotrauma.ship;

import com.opamb.barotrauma.blockentity.ShipCoreBlockEntity;
import com.opamb.barotrauma.network.ShipWaterStatePacket;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 一艘船的运行时状态：几何（舱室）+ 机器统计 + 数值模拟。
 */
public final class ShipState {

    private ShipGeometry.Result geometry;
    private final MachineCount machines;
    /** 一个破口：位置 + 朝船体内部（密封舱室质心）的水平单位方向。 */
    public record Breach(BlockPos pos, float dirX, float dirZ) {
    }

    /**
     * 破口（装配/世界坐标）。只记录真正连到外海的洞，客户端在洞口画水流/气泡；
     * 内部隔墙被砸出的洞不算，避免在舱室中间凭空冒泡。
     */
    private final Map<BlockPos, Breach> breaches = new LinkedHashMap<>();
    private ShipSimulation simulation;
    private ShipWater water;
    /** 逐方块船壳耐久（剩余比例）。重扫/平移/卸船都要跟着走。 */
    private final HullDamage hullDamage = new HullDamage();
    /**
     * 船体加固倍率：原版 {@code increasewallhealth} 每级 +20%。
     * T1 最多 2 级、T2 4 级、T3 6 级；现在是预留接口，默认 1.0。
     */
    private float hullHealthMultiplier = 1.0F;

    /** 10% 伤害开始的小漏：2.1 格³/秒/处（约等于完整破口 35% 的流量）。 */
    private static final double SMALL_LEAK_UNITS_PER_SECOND = 210.0D;
    /** 70% 伤害开始的大漏：4.5 格³/秒/处（约等于完整破口 75% 的流量）。 */
    private static final double BIG_LEAK_UNITS_PER_SECOND = 450.0D;
    /** 漏水的空间缓存：舱室格 → 舱室下标；以及所有“船内空气格”（含连海空腔）。 */
    private Map<BlockPos, Integer> leakCompartmentByCell;
    private Set<BlockPos> leakInsideCells;

    /**
     * 水面包缓存（{@code shipId=-1}、方块船形态）。
     *
     * <p>干燥区判定每 tick 每个玩家都要问一次“我在船舱里吗”，原来每次都重新
     * {@link ShipWaterStateBuilder#build}——里面要跑贪心矩形分解和连海空腔的 64 轮松弛，
     * 属于纯重复劳动。这里按游戏刻缓存，同一 tick 内无论多少个玩家都只算一次。</p>
     */
    private ShipWaterStatePacket waterBoxCache;
    private long waterBoxCacheTick = Long.MIN_VALUE;

    private int throttleX;
    private int throttleY;
    private int throttleZ;
    private int moveTimer;
    /** 连续运动模型：记录物理船回写的速度（方块船自己不再移动）。 */
    private ShipPhysics physics = new ShipPhysics();
    /** 推进功率 5..100（%），决定每秒挪几格。 */
    private int power = 100;
    /** 自动驾驶：朝声纳上标记的目标点逼近。 */
    private boolean autopilotHold;
    /** 自动驾驶目标（世界坐标）；null = 还没标记。 */
    private net.minecraft.core.BlockPos autopilotTarget;

    public ShipState(ShipGeometry.Result geometry, MachineCount machines) {
        this.geometry = geometry;
        this.machines = machines;
    }

    public ShipGeometry.Result geometry() {
        return geometry;
    }

    public HullDamage hullDamage() {
        return hullDamage;
    }

    /** 该方块的最大血量（基础值 × 船体加固倍率）；不可破坏的方块返回 0。 */
    public float hullMaxHealth(net.minecraft.world.level.block.state.BlockState state) {
        return com.opamb.barotrauma.registry.ModShipBlocks.maxHealth(state) * hullHealthMultiplier;
    }

    public float hullHealthMultiplier() {
        return hullHealthMultiplier;
    }

    public void setHullHealthMultiplier(float multiplier) {
        this.hullHealthMultiplier = net.minecraft.util.Mth.clamp(multiplier, 0.25F, 5.0F);
    }

    /** 这个方块是不是当前船体几何里的一格结构（用于拒绝空气/隔墙上的伤害）。 */
    public boolean tracksHullBlock(net.minecraft.core.BlockPos pos) {
        return geometry.hull().contains(pos);
    }

    public MachineCount machines() {
        return machines;
    }

    public List<ShipGeometry.Compartment> compartments() {
        return geometry.compartments();
    }

    /**
     * 船体破损后替换几何（舱室 / 连海空腔全部重算）。
     *
     * <p>模拟对象要重建（舱室数组长度变了），但反应堆、压载、水泵这些设置
     * 从旧实例拷过来；密封舱室里的旧水不保留——破口舱室会从 0 开始由海水灌入，
     * 这比"把旧水位硬塞进新舱室"的行为可预期。</p>
     */
    public void replaceGeometry(ShipGeometry.Result next) {
        ShipSimulation old = this.simulation;
        double oldSealedWater = old == null ? 0.0D : old.waterUnits();
        double oldPocketWater = old == null ? 0.0D : old.pocketWaterTotal();
        this.geometry = next;
        // 已经打没的方块不再属于新几何；把它们的耐久记录清掉，客户端不会在空气里画血条。
        this.hullDamage.retain(next.hull()::contains);
        this.leakCompartmentByCell = null;
        this.leakInsideCells = null;
        this.waterBoxCache = null;
        this.waterBoxCacheTick = Long.MIN_VALUE;
        this.simulation = new ShipSimulation(next, machines);
        this.simulation.copySettingsFrom(old);
        // 连续砸洞时水位不能归零：把旧水量按新几何的容积重新分配
        this.simulation.seedWater(oldSealedWater, oldPocketWater);
        this.water = null;
    }

    public ShipSimulation simulation() {
        if (simulation == null) {
            simulation = new ShipSimulation(geometry, machines);
        }
        return simulation;
    }

    /**
     * 当前游戏刻的水面包（方块船形态），同一 tick 内复用。
     *
     * <p>干燥区判定每个玩家每 tick 都要用一次，而 {@code ShipWaterStateBuilder.build}
     * 里有贪心矩形分解和连海空腔松弛，代价远高于一次包围盒判定。这里按 tick 缓存：
     * 同一 tick 的所有调用共用一份结果；下一个 tick 自然重算，水位变化最多延迟 1 tick
     * （20 Hz），和 10 Hz 的网络同步相比看不出差别。</p>
     *
     * <p>调用方必须只在服务端持有 {@link ShipState} 的时候用它——客户端没有这个对象。</p>
     */
    public ShipWaterStatePacket waterBoxesFor(long gameTime) {
        if (waterBoxCache != null && waterBoxCacheTick == gameTime) {
            return waterBoxCache;
        }
        waterBoxCache = ShipWaterStateBuilder.build(this, -1L, false, 0.0F, 0.0F, 0.0F);
        waterBoxCacheTick = gameTime;
        return waterBoxCache;
    }

    /** 连到外海的破口；随水包发给客户端，用来在洞口画水花/气泡。 */
    public Collection<Breach> breaches() {
        return breaches.values();
    }

    /** 方块焊回去之后，把对应破口从列表里移除（否则客户端会在补好的位置继续画水花）。 */
    public void removeBreaches(Collection<BlockPos> positions) {
        if (positions == null) {
            return;
        }
        for (BlockPos pos : positions) {
            breaches.remove(pos);
        }
    }

    public void removeBreach(BlockPos pos) {
        if (pos != null) {
            breaches.remove(pos);
        }
    }

    /**
     * 记录新破口。只有落在连海空腔（{@code openPockets}）里的洞才会真正进水，
     * 内部隔墙的洞不记录，避免客户端在舱室中间画错位置。
     *
     * <p>方向取“洞口 → 密封舱室质心”的水平单位向量：客户端用它把水花/气泡朝舱内推，
     * 不再靠 bbox 中心猜（连海空腔的 bbox 会包含船外空气，容易指向舱外）。</p>
     */
    public void addBreaches(Collection<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) {
            return;
        }
        for (BlockPos pos : positions) {
            if (!isSeaConnected(pos) || breaches.containsKey(pos)) {
                continue;
            }
            // 优先用破口所在空腔的质心：整个船的质心可能指向别的密封舱，
            // 客户端水花/气泡就会朝错方向喷。
            double[] center = pocketCenter(pos);
            if (center == null) {
                center = interiorCenter();
            }
            double dx = center[0] - (pos.getX() + 0.5D);
            double dz = center[1] - (pos.getZ() + 0.5D);
            double length = Math.sqrt(dx * dx + dz * dz);
            float dirX = length > 1.0E-4D ? (float) (dx / length) : 0.0F;
            float dirZ = length > 1.0E-4D ? (float) (dz / length) : 0.0F;
            BlockPos immutable = pos.immutable();
            breaches.put(immutable, new Breach(immutable, dirX, dirZ));
        }
    }

    /** 破口所在连海空腔的水平质心；不在任何空腔里返回 null。 */
    private double[] pocketCenter(BlockPos pos) {
        for (ShipGeometry.Compartment pocket : geometry.openPockets()) {
            if (!pocket.cells().contains(pos)) {
                continue;
            }
            double x = 0.0D;
            double z = 0.0D;
            long count = 0L;
            for (BlockPos cell : pocket.cells()) {
                x += cell.getX() + 0.5D;
                z += cell.getZ() + 0.5D;
                count++;
            }
            if (count > 0L) {
                return new double[]{x / count, z / count};
            }
        }
        return null;
    }

    /** 密封舱室在水平面上的质心；没有密封舱室时退回整船包围盒中心。 */
    private double[] interiorCenter() {
        double x = 0.0D;
        double z = 0.0D;
        long count = 0L;
        for (ShipGeometry.Compartment compartment : geometry.compartments()) {
            for (BlockPos pos : compartment.cells()) {
                x += pos.getX() + 0.5D;
                z += pos.getZ() + 0.5D;
                count++;
            }
        }
        if (count == 0L) {
            ShipGeometry.Bounds bounds = geometry.bounds();
            x = (bounds.minX() + bounds.maxX() + 1) * 0.5D;
            z = (bounds.minZ() + bounds.maxZ() + 1) * 0.5D;
            count = 1L;
        }
        return new double[]{x / count, z / count};
    }

    private boolean isSeaConnected(BlockPos pos) {
        for (ShipGeometry.Compartment pocket : geometry.openPockets()) {
            if (pocket.cells().contains(pos)) {
                return true;
            }
        }
        return false;
    }

    /** 船体是否还完整（有方块被破坏 → 需要重新分舱）。 */
    public boolean hullIntact(net.minecraft.world.level.Level level) {
        for (net.minecraft.core.BlockPos pos : geometry.hull()) {
            if (!com.opamb.barotrauma.registry.ModShipBlocks.isShipBlock(level.getBlockState(pos))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 收集这艘船在世界上占用的**全部**方块。
     *
     * <p>不能用 {@code geometry.hull()}：那是"最大连通分量"，船上有独立的小方块组
     * （门、玻璃、单独挂着的机器）时会漏掉，搬船/装船之后那些方块就**留在原地**——
     * 表现出来就是"船开走了，原地还留着一截"。所以扫包围盒里的所有船方块。</p>
     */
    public java.util.Set<net.minecraft.core.BlockPos> collectBlocks(net.minecraft.world.level.Level level) {
        java.util.Set<net.minecraft.core.BlockPos> out = new java.util.HashSet<>();
        ShipGeometry.Bounds b = geometry.bounds();
        for (net.minecraft.core.BlockPos pos : net.minecraft.core.BlockPos.betweenClosed(
                b.minX() - 1, b.minY() - 1, b.minZ() - 1,
                b.maxX() + 1, b.maxY() + 1, b.maxZ() + 1)) {
            if (com.opamb.barotrauma.registry.ModShipBlocks.isShipBlock(level.getBlockState(pos))) {
                out.add(pos.immutable());
            }
        }
        return out;
    }

    /** 设定推进方向（每轴 −1/0/1）。 */
    public void setThrottle(int x, int y, int z) {
        throttleX = x;
        throttleY = y;
        throttleZ = z;
        moveTimer = 0;
        if (x != 0 || y != 0 || z != 0) {
            // 手动操舵即退出自动驾驶（wiki 里导航终端也是手动/自动二选一）
            autopilotHold = false;
        }
    }

    /** 自动驾驶开关。关掉只停船，标记点保留（下次打开继续前往）。 */
    public void setAutopilotHold(boolean hold) {
        this.autopilotHold = hold;
        this.throttleX = 0;
        this.throttleY = 0;
        this.throttleZ = 0;
        this.moveTimer = 0;
    }

    /** 在声纳上标记目标点：自动驾驶随即朝它开过去。 */
    public void setAutopilotTarget(net.minecraft.core.BlockPos target) {
        this.autopilotTarget = target;
        this.autopilotHold = true;
        throttleX = 0;
        throttleY = 0;
        throttleZ = 0;
        moveTimer = 0;
    }

    public void clearAutopilotTarget() {
        this.autopilotTarget = null;
        throttleX = 0;
        throttleY = 0;
        throttleZ = 0;
    }

    @javax.annotation.Nullable
    public net.minecraft.core.BlockPos autopilotTarget() {
        return autopilotTarget;
    }

    public boolean autopilotHold() {
        return autopilotHold;
    }

    /** 距离目标点还有多远（格）；没有目标返回 -1。 */
    public int autopilotDistance(net.minecraft.core.BlockPos corePos) {
        return autopilotTarget == null ? -1 : (int) Math.round(Math.sqrt(autopilotTarget.distSqr(corePos)));
    }

    /** 自动驾驶：按目标点设推力；到点后推力归零即"保持"。 */
    private void aimAtTarget(net.minecraft.core.BlockPos corePos) {        if (autopilotTarget == null) {
            throttleX = 0;
            throttleY = 0;
            throttleZ = 0;
            return;
        }
        int dx = autopilotTarget.getX() - corePos.getX();
        int dy = autopilotTarget.getY() - corePos.getY();
        int dz = autopilotTarget.getZ() - corePos.getZ();
        // 差 3 格以内不再纠偏，避免在目标点附近来回抖
        throttleX = Math.abs(dx) >= 3 ? Integer.signum(dx) : 0;
        throttleY = Math.abs(dy) >= 3 ? Integer.signum(dy) : 0;
        throttleZ = Math.abs(dz) >= 3 ? Integer.signum(dz) : 0;
    }

    /**
     * 物理船用：自动驾驶朝目标点推一步（写 throttle，但不经过 {@link #setThrottle}，
     * 否则会被当成手动操舵、把自动驾驶关掉）。
     *
     * @param currentPos 船（声纳）当前的世界坐标
     */
    /**
     * 物理船用：自动驾驶朝目标点推一步，**带惯性补偿**。
     *
     * <p>旧版只按"目标在哪边"给 ±1 推力，船有速度时会在目标附近来回过冲。
     * 现在按物理船的 PD 控制：期望速度 = 位置误差 × 增益（限幅），
     * 推力方向 = 期望速度 − 当前速度，所以接近目标时会提前反向刹车。</p>
     *
     * <p>不经过 {@link #setThrottle}，否则会被当成手动操舵、把自动驾驶关掉。</p>
     */
    public void autopilotStep(double x, double y, double z,
                             double vx, double vy, double vz) {
        if (!autopilotHold || autopilotTarget == null) {
            return;
        }
        // 推进器满功率能给出的速度（和 VsThruster 的 ACCEL 匹配）
        double maxHorizontal = 6.0D;
        double maxVertical = 2.5D;
        double gain = 0.5D;
        double deadband = 0.15D;

        double ex = autopilotTarget.getX() + 0.5D - x;
        double ey = autopilotTarget.getY() + 0.5D - y;
        double ez = autopilotTarget.getZ() + 0.5D - z;

        double dvx = clamp(ex * gain, -maxHorizontal, maxHorizontal) - vx;
        double dvy = clamp(ey * gain, -maxVertical, maxVertical) - vy;
        double dvz = clamp(ez * gain, -maxHorizontal, maxHorizontal) - vz;

        throttleX = Math.abs(dvx) > deadband ? (int) Math.signum(dvx) : 0;
        throttleY = Math.abs(dvy) > deadband ? (int) Math.signum(dvy) : 0;
        throttleZ = Math.abs(dvz) > deadband ? (int) Math.signum(dvz) : 0;

        // 到点附近且基本停住：清掉目标，避免原地抖
        if (Math.abs(ex) < 1.5D && Math.abs(ey) < 1.5D && Math.abs(ez) < 1.5D
                && Math.abs(vx) + Math.abs(vy) + Math.abs(vz) < 0.6D) {
            throttleX = 0;
            throttleY = 0;
            throttleZ = 0;
            autopilotTarget = null;
            autopilotHold = false;
        }
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }

    public boolean throttled() {
        return throttleX != 0 || throttleY != 0 || throttleZ != 0;
    }

    /**
     * 观察用：按当前目标点算一遍推力，不移动船、不改变现有推力。
     *
     * <p>自检拿它验证"目标在正东 → 推力 +X""已经在目标点 2 格内 → 推力为零"。</p>
     */
    public int[] previewAutopilot(net.minecraft.core.BlockPos corePos) {
        int sx = throttleX;
        int sy = throttleY;
        int sz = throttleZ;
        aimAtTarget(corePos);
        int[] out = {throttleX, throttleY, throttleZ};
        throttleX = sx;
        throttleY = sy;
        throttleZ = sz;
        return out;
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

    /** 推进功率（%）。 */
    public void setPower(int percent) {
        this.power = net.minecraft.util.Mth.clamp(percent, 5, 100);
    }

    public int power() {
        return power;
    }

    /** 当前推力对应的航向名（水平八向）。 */
    public String headingName() {
        if (autopilotHold) {
            if (autopilotTarget == null) {
                return "自动待命";
            }
            return throttled() ? "自动·前往标记" : "自动·已到达";
        }
        if (throttleX == 0 && throttleZ == 0) {
            return throttleY > 0 ? "上浮" : throttleY < 0 ? "下潜" : "停车";
        }
        String vertical = throttleY > 0 ? " + 上浮" : throttleY < 0 ? " + 下潜" : "";
        if (throttleZ < 0) {
            return (throttleX == 0 ? "北" : throttleX > 0 ? "东北" : "西北") + vertical;
        }
        if (throttleZ > 0) {
            return (throttleX == 0 ? "南" : throttleX > 0 ? "东南" : "西南") + vertical;
        }
        return (throttleX > 0 ? "东" : "西") + vertical;
    }

    public String throttleName() {
        if (!throttled()) {
            return "停车";
        }
        return headingName() + "  (" + throttleX + "," + throttleY + "," + throttleZ + ")";
    }

    /** 运动模型（只读，给面板/自检看速度和航向）。 */
    public ShipPhysics physics() {
        return physics;
    }

    /** 当前航速（格/秒，1 格 = 1 米）。有惯性，收油门后还会滑一段。 */
    public double blocksPerSecond() {
        return physics.speed();
    }

    /** 舱室总进水比例（0..1），用于让灌了水的船往下沉。 */
    public double floodRatio() {
        // 分母必须把"连海空腔"也算上：舱室破洞后会从 compartments 移到 openPockets，
        // 只数密闭舱室的话，整艘船灌满水时比例反而是 0，物理上不会下沉。
        double volume = 0.0D;
        for (ShipGeometry.Compartment compartment : compartments()) {
            volume += compartment.volume();
        }
        for (ShipGeometry.Compartment pocket : geometry.openPockets()) {
            volume += pocket.volume();
        }
        if (volume <= 0.0D) {
            return 0.0D;
        }
        double units = simulation().waterUnits() + simulation().pocketWaterTotal();
        double capacity = volume * ShipSimulation.UNITS_PER_BLOCK;
        return capacity <= 0.0D ? 0.0D : Math.min(1.0D, units / capacity);
    }

    /**
     * 给客户端水花用的泄漏强度（0 = 不漏，0.35 = 小漏，1 = 大漏/已破口）。
     *
     * <p>只对“有一面朝船外、相邻还有密封舱”的受损格返回非零；0% 的 breached
     * 也返回 1，让客户端继续在洞口喷水花（真正的水量由 tickSeaFlood 管）。</p>
     */
    public float leakIntensity(BlockPos pos) {
        if (pos == null) {
            return 0.0F;
        }
        float health = hullDamage.health(pos);
        if (health > 0.9F) {
            return 0.0F;
        }
        ensureLeakCache();
        if (!touchesOutside(pos)) {
            return 0.0F;
        }
        if (health <= 0.0F) {
            return 1.0F;
        }
        if (adjacentCompartment(pos) < 0) {
            return 0.0F;
        }
        return health <= 0.3F ? 1.0F : 0.35F;
    }

    /**
     * 船壳渗漏：剩余耐久 90% 以下（伤害 ≥10%）开始小漏，30% 以下（伤害 ≥70%）变大漏。
     *
     * <p>0% 不走这里：那会把方块切成 {@code breached}，几何扫描直接把它当空气，
     * 舱室变成连海空腔，由 {@link ShipSimulation#tickSeaFlood} 按洞口灌水。</p>
     */
    public void tickLeaks(double dt) {
        if (dt <= 0.0D || hullDamage.isEmpty()) {
            return;
        }
        ensureLeakCache();
        ShipSimulation sim = simulation();
        for (Map.Entry<BlockPos, Float> entry : hullDamage.entries().entrySet()) {
            float health = entry.getValue();
            if (health <= 0.0F || health > 0.9F) {
                continue;
            }
            BlockPos pos = entry.getKey();
            if (!touchesOutside(pos)) {
                continue;
            }
            int compartment = adjacentCompartment(pos);
            if (compartment < 0) {
                continue;
            }
            double rate = health <= 0.3F ? BIG_LEAK_UNITS_PER_SECOND
                    : SMALL_LEAK_UNITS_PER_SECOND;
            sim.addWater(compartment, rate * dt);
        }
    }

    /** 舱室格 → 下标 + 船内空气格集合；几何变化时由 replaceGeometry 清空。 */
    private void ensureLeakCache() {
        if (leakCompartmentByCell != null && leakInsideCells != null) {
            return;
        }
        Map<BlockPos, Integer> byCell = new HashMap<>();
        Set<BlockPos> inside = new HashSet<>();
        List<ShipGeometry.Compartment> rooms = compartments();
        for (int i = 0; i < rooms.size(); i++) {
            for (BlockPos cell : rooms.get(i).cells()) {
                byCell.put(cell, i);
                inside.add(cell);
            }
        }
        for (ShipGeometry.Compartment pocket : geometry.openPockets()) {
            inside.addAll(pocket.cells());
        }
        leakCompartmentByCell = byCell;
        leakInsideCells = inside;
    }

    /** 这一格船壳是否有一面直接朝船外（既不是船方块，也不是船内空气）。 */
    private boolean touchesOutside(BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.relative(direction);
            if (!leakInsideCells.contains(neighbor) && !geometry.hull().contains(neighbor)) {
                return true;
            }
        }
        return false;
    }

    private int adjacentCompartment(BlockPos pos) {
        for (Direction direction : Direction.values()) {
            Integer index = leakCompartmentByCell.get(pos.relative(direction));
            if (index != null) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 平移后的船状态：几何整体偏移，模拟实例原样带过去。
     *
     * <p>船是刚体，挪一格不改变舱室，所以不需要重扫；温度和舱内水位也跟着保留——
     * 否则每推进一格反应堆都会从 3000°C 重新开始。</p>
     */
    public ShipState shifted(int dx, int dy, int dz) {
        ShipState next = new ShipState(geometry.shifted(dx, dy, dz), machines);
        next.simulation = this.simulation;
        // 船壳耐久是逐格记录的，整船平移时必须跟着坐标一起挪。
        next.hullDamage.copyFrom(this.hullDamage);
        next.hullDamage.shift(dx, dy, dz);
        next.hullHealthMultiplier = this.hullHealthMultiplier;
        // 速度/惯性必须跟着船走，否则每挪一格船就"停"一次
        next.physics = this.physics;
        next.throttleX = throttleX;
        next.throttleY = throttleY;
        next.throttleZ = throttleZ;
        next.power = power;
        next.autopilotHold = autopilotHold;
        next.autopilotTarget = autopilotTarget;
        return next;
    }

    /**
     * 方块船推进：先积分连续速度，攒够一格才真的挪一格。
     *
     * <p>2026-11-02 恢复：用户选择"方块留在世界里"的路线——船壳真挡水、能交互、能做破损。
     * VS2 物理船代码保留但不再作为主路径。</p>
     */
    public net.minecraft.core.BlockPos tickMovement(net.minecraft.server.level.ServerLevel level,
                                                    net.minecraft.core.BlockPos corePos) {
        if (autopilotHold) {
            aimAtTarget(corePos);
        }
        double dt = 0.1D;
        if (autopilotHold && autopilotTarget != null) {
            // P 控制：离目标越远目标速度越大；靠近时目标速度自己收敛到 0，不用额外刹车
            double gain = 0.7D * power / 100.0D;
            double tx = net.minecraft.util.Mth.clamp(
                    (autopilotTarget.getX() - corePos.getX()) * gain, -ShipPhysics.MAX_SPEED, ShipPhysics.MAX_SPEED);
            double ty = net.minecraft.util.Mth.clamp(
                    (autopilotTarget.getY() - corePos.getY()) * gain, -ShipPhysics.MAX_RISE, ShipPhysics.MAX_RISE);
            double tz = net.minecraft.util.Mth.clamp(
                    (autopilotTarget.getZ() - corePos.getZ()) * gain, -ShipPhysics.MAX_SPEED, ShipPhysics.MAX_SPEED);
            physics.tickAuto(dt, tx, ty, tz);
        } else {
            physics.tickManual(dt, throttleX, throttleY, throttleZ, power, 0.0D, floodRatio());
        }
        return applyStep(level, corePos);
    }

    private net.minecraft.core.BlockPos applyStep(net.minecraft.server.level.ServerLevel level,
                                                  net.minecraft.core.BlockPos corePos) {
        int dx = physics.stepX();
        int dy = physics.stepY();
        int dz = physics.stepZ();
        if (dx == 0 && dy == 0 && dz == 0) {
            return corePos;
        }
        if (ShipMover.move(level, corePos, dx, dy, dz).ok()) {
            physics.consume(dx, dy, dz);
            return corePos.offset(dx, dy, dz);
        }
        // 整步被挡：拆成三个单轴分别试，撞上的那轴停车，其余轴照走（贴墙滑行）
        net.minecraft.core.BlockPos pos = corePos;
        pos = slide(level, pos, dx, 0, 0);
        pos = slide(level, pos, 0, dy, 0);
        pos = slide(level, pos, 0, 0, dz);
        return pos;
    }

    private net.minecraft.core.BlockPos slide(net.minecraft.server.level.ServerLevel level,
                                              net.minecraft.core.BlockPos corePos, int dx, int dy, int dz) {
        if (dx == 0 && dy == 0 && dz == 0) {
            return corePos;
        }
        if (ShipMover.move(level, corePos, dx, dy, dz).ok()) {
            physics.consume(dx, dy, dz);
            return corePos.offset(dx, dy, dz);
        }
        int axis = dx != 0 ? 0 : dy != 0 ? 1 : 2;
        double hitSpeed = Math.abs(physics.speedOn(axis));
        physics.blocked(axis);
        if (hitSpeed > 1.0D) {
            damageFront(level, dx, dy, dz, hitSpeed);
        }
        if (axis == 0) {
            throttleX = 0;
        } else if (axis == 1) {
            throttleY = 0;
        } else {
            throttleZ = 0;
        }
        if (autopilotHold) {
            autopilotTarget = null;
        }
        return corePos;
    }

    /** 撞墙：迎面船壳按撞击速度扣耐久，0 耐久才破口（不再一击秒掉）。 */
    private void damageFront(net.minecraft.server.level.ServerLevel level, int dx, int dy, int dz,
                             double hitSpeed) {
        if (geometry.hull().isEmpty()) {
            return;
        }
        int best = Integer.MIN_VALUE;
        net.minecraft.core.BlockPos target = null;
        for (net.minecraft.core.BlockPos pos : geometry.hull()) {
            // 声纳是船脑，不能当迎撞面被撞掉
            if (level.getBlockState(pos).is(com.opamb.barotrauma.registry.ModShipBlocks.SONAR.get())) {
                continue;
            }
            int score = pos.getX() * dx + pos.getY() * dy + pos.getZ() * dz;
            if (score > best) {
                best = score;
                target = pos;
            }
        }
        if (target == null) {
            return;
        }
        ShipCoreBlockEntity core = ShipCores.nearestCore(level, target, 64.0D);
        float amount = (float) (15.0D + hitSpeed * 10.0D);
        if (core != null) {
            // 有船核心：只走耐久，0 耐久才由核心破口；不能在这里直接删方块。
            core.damageBlock(level, target, amount, null);
            return;
        }
        // 没有船核心（老存档/独立结构）才退回直接删除，避免完全失去碰撞破损。
        level.setBlock(target, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
    }

    /** 舱室水位 → 真实水方块。 */
    public int applyWater(net.minecraft.world.level.Level level) {
        if (water == null) {
            water = new ShipWater(geometry, simulation());
        }
        return water.apply(level);
    }

    /**
     * 把舱内（和连海空腔）的水方块全部清掉。
     *
     * <p>装成物理船之前必须调一次：VS2 的装配集合只有"船体方块"，舱内的水不在里面，
     * 会原地剩下一坨水——看起来就是"船没了、水灌进来了"。拆船的时候也不该带着水回来。</p>
     */
    public int clearWater(net.minecraft.world.level.Level level) {
        return clearWater(level, 0, 0, 0);
    }

    /** 带整数位移的舱室排水（拆船铺回方块后，位置 = 原坐标 + 船位移）。 */
    public int clearWater(net.minecraft.world.level.Level level, int dx, int dy, int dz) {
        if (water == null) {
            water = new ShipWater(geometry, simulation());
        }
        return water.clear(level, dx, dy, dz);
    }

    /** 静态摘要：舱室 + 设备。机器方块已删除，只报声纳和舱室。 */
    public Component summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("船体扫描：").append(geometry.compartments().size()).append(" 个舱室");
        sb.append("，船体方块 ").append(geometry.hullBlocks());
        sb.append("，舱内容积 ").append(geometry.totalVolume()).append(" 格");
        sb.append(geometry.sealed() ? "，密闭正常" : "，有 " + geometry.openPockets().size() + " 处连海");
        Component head = Component.literal(sb.toString())
                .withStyle(geometry.sealed() ? ChatFormatting.AQUA : ChatFormatting.GOLD);

        Component body = Component.literal("\n  设备：" + machines.describe()).withStyle(ChatFormatting.GRAY);
        int index = 1;
        for (ShipGeometry.Compartment compartment : compartments()) {
            int waterPercent = (int) Math.round(compartment.waterRatio() * 100.0D);
            body = body.copy().append(Component.literal("\n  #" + index + " 容积 " + compartment.volume()
                    + " 格，进水 " + waterPercent + "%").withStyle(ChatFormatting.DARK_GRAY));
            index++;
        }
        return head.copy().append(body);
    }

    /** 当前实时数值（不推进时间），用于面板/命令。机器删了，只报水、航向和推力。 */
    public Component liveReport() {
        ShipSimulation.Report report = simulation().report();
        String text = String.format(Locale.ROOT,
                "实时｜舱内水 %.0f 单位｜进水 %d%%｜%s %d%%",
                report.waterUnits(), (int) Math.round(floodRatio() * 100.0D),
                headingName(), power);
        return Component.literal(text).withStyle(ChatFormatting.GREEN);
    }

    /** 快进 {@code seconds} 秒（固定 0.1 s 步长），返回一行报告。 */
    public Component simulate(double seconds) {
        ShipSimulation sim = simulation();
        int steps = Math.max(1, (int) Math.round(seconds * 10.0D));
        for (int i = 0; i < steps; i++) {
            sim.tick(0.1D);
        }
        ShipSimulation.Report report = sim.report();

        ChatFormatting color = report.meltingDown() ? ChatFormatting.RED
                : report.overheated() ? ChatFormatting.GOLD : ChatFormatting.AQUA;
        String phase = switch (report.airlockPhase()) {
            case DRAINING -> "抽干中";
            case FLOODING -> "注水中";
            default -> "关闭";
        };
        String text = String.format(Locale.ROOT,
                "模拟 %.1fs｜温度 %.0f°C｜功率 %.0f/%.0f kW (%d%%)｜氧气 %d%%｜舱内水 %.0f 单位｜压载 %d%%｜气闸 %d%%(%s×%d)",
                seconds, report.temperature(), report.powerOutput(), report.powerDemand(),
                (int) Math.round(report.powerRatio() * 100.0D), (int) Math.round(report.oxygenPercent() * 100.0D),
                report.waterUnits(), (int) Math.round(report.ballastPercent() * 100.0D),
                (int) Math.round(report.airlockPercent() * 100.0D), phase, report.airlockCycles());
        Component line = Component.literal(text).withStyle(color);
        if (report.meltingDown()) {
            line = line.copy().append(Component.literal("  ⚠ 熔毁！").withStyle(ChatFormatting.RED));
        } else if (report.overheated()) {
            line = line.copy().append(Component.literal("  ⚠ 过热").withStyle(ChatFormatting.GOLD));
        }
        return line;
    }
}
