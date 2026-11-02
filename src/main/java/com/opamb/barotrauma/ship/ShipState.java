package com.opamb.barotrauma.ship;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * 一艘船的运行时状态：几何（舱室）+ 机器统计 + 数值模拟。
 */
public final class ShipState {

    private ShipGeometry.Result geometry;
    private final MachineCount machines;
    private ShipSimulation simulation;
    private ShipWater water;
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
     * 平移后的船状态：几何整体偏移，模拟实例原样带过去。
     *
     * <p>船是刚体，挪一格不改变舱室，所以不需要重扫；温度和舱内水位也跟着保留——
     * 否则每推进一格反应堆都会从 3000°C 重新开始。</p>
     */
    public ShipState shifted(int dx, int dy, int dz) {
        ShipState next = new ShipState(geometry.shifted(dx, dy, dz), machines);
        next.simulation = this.simulation;
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
            damageFront(level, dx, dy, dz);
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

    /** 撞墙：把迎面的船体方块打掉一格。 */
    private void damageFront(net.minecraft.server.level.ServerLevel level, int dx, int dy, int dz) {
        if (geometry.hull().isEmpty()) {
            return;
        }
        int best = Integer.MIN_VALUE;
        net.minecraft.core.BlockPos target = null;
        for (net.minecraft.core.BlockPos pos : geometry.hull()) {
            int score = pos.getX() * dx + pos.getY() * dy + pos.getZ() * dz;
            if (score > best) {
                best = score;
                target = pos;
            }
        }
        if (target != null) {
            level.setBlock(target, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        }
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
