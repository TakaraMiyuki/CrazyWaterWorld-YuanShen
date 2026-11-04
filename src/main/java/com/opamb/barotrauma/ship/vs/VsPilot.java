package com.opamb.barotrauma.ship.vs;

import com.opamb.barotrauma.block.HullBlock;
import com.opamb.barotrauma.blockentity.ShipCoreBlockEntity;
import com.opamb.barotrauma.data.ShipSavedData;
import com.opamb.barotrauma.network.ModNetwork;
import com.opamb.barotrauma.network.ShipHullDamagePacket;
import com.opamb.barotrauma.network.ShipWaterStatePacket;
import com.opamb.barotrauma.registry.ModShipBlocks;
import com.opamb.barotrauma.ship.ShipGeometry;
import com.opamb.barotrauma.ship.MachineCount;
import com.opamb.barotrauma.ship.ShipSimulation;
import com.opamb.barotrauma.ship.ShipState;
import com.opamb.barotrauma.world.OceanEnvironment;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.mod.common.world.RaycastUtilsKt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 物理船（VS2）的运行时：装配、推进、拆回方块。
 *
 * <p>这一版的核心约定是<strong>"一套移动系统"</strong>：船一旦被收成 VS2 刚体，
 * 方块那一套（{@code ShipMover} 平移、{@code ShipWater} 写水位、破口重扫）就通过
 * {@link VsState} 整段停掉，运动只由刚体算。之前这两套同时在跑，
 * 表现出来就是"船同时被方块逻辑和物理逻辑搬来搬去"。</p>
 *
 * <p>方块进了刚体之后并没有从世界消失，而是被 VS2 搬到船坞区块里、仍然在 tick。
 * 所以"这个方块属于物理船"这件事可以直接问 VS2（{@link VsBridge#managingShip}），
 * 不需要我们自己记坐标——这也让同世界的其它方块船不受影响。</p>
 *
 * <p>拆船：VS2 没有"刚体还原成方块"的 API（{@code deleteShip} 的第三个参数是
 * "把世界的方块挖掉"，不是"铺回方块"），所以装配时留一份方块快照，拆的时候按
 * 船自装配以来的整数位移铺回世界。</p>
 */
public final class VsPilot {

    private static ServerShip ship;
    private static VsThruster thruster;
    /** 装船那一刻的船状态。方块收进刚体后世界里找不到核心，面板和操舵靠它。 */
    private static ShipState state;
    /** 装配时那台声纳（船脑）的世界坐标。 */
    private static BlockPos corePos;
    /** 装配时船中心的世界坐标——拆船时用它算"船开出去多远"。 */
    private static Vec3 assembledPos = Vec3.ZERO;
    /** 装配时的方块快照（位置 → 方块状态），拆船时原样铺回。 */
    private static Map<BlockPos, BlockState> snapshot = Map.of();
    /** 装配时的船体方块数——和当前快照的差就是被砸掉的格数（破口大小）。 */
    private static int initialShipBlocks;
    /** 装配时的舱室格子（世界坐标）——船移动/拆船时把舱内积水清成空气。 */
    private static java.util.List<BlockPos> interiorCells = java.util.List.of();
    /**
     * 流体拦截格 = 舱室格向外扩 1 圈（船壳层）。
     *
     * <p>船是连续坐标渲染的，拦截只能按整数格。只护舱室时，船 y 不是整数，
     * 舱底会漏出半层没被保护的空间，真实海水从破口钻进来，在舱底形成一小滩水。
     * 多护一圈船壳格，漏网的半层就落在船壳里（被船壳方块挡住），舱室本身保持真空气。</p>
     */
    private static java.util.List<BlockPos> guardCells = java.util.List.of();
    /** 水面状态同步计时。 */
    private static int waterSyncTimer;
    /** 最近一次发给客户端的水面数据；服务端判断“玩家在哪个舱室、是否已进水”时复用。 */
    private static ShipWaterStatePacket lastWaterPacket = ShipWaterStatePacket.empty();
    /** 上一次清舱内水用的整数偏移，避免每 tick 重复扫 273 格。 */
    private static int interiorOffsetX = Integer.MIN_VALUE;
    private static int interiorOffsetY = Integer.MIN_VALUE;
    private static int interiorOffsetZ = Integer.MIN_VALUE;
    /** 装配时声纳台（船脑）的世界坐标——交互锚点放在这里，跟着船整格移动。 */
    private static BlockPos sonarAnchor;
    /** 当前交互锚点实际所在的位置。 */
    private static BlockPos proxyPos;
    /** 上一次 launch 用的维度。 */
    private static ServerLevel lastLevel;
    /**
     * 船坞平移量：船坞坐标 = 装配时的世界坐标 + 这个偏移。
     *
     * <p>VS2 装配把方块整体搬到远处的船坞区块。核心方块实体在船坞里 tick 时
     * 把真实坐标报上来（{@link VsState#observePhysicalCore}），这里减去装配时的
     * 声纳坐标就得到平移量。有了它才能读写船坞里的船体方块。</p>
     */
    private static int yardOffsetX = Integer.MIN_VALUE;
    private static int yardOffsetY = Integer.MIN_VALUE;
    private static int yardOffsetZ = Integer.MIN_VALUE;
    /** 什么时候开始允许船体破损对账（给 VS2 搬完方块留 1 秒）。 */
    private static long hullCheckStartGameTime = Long.MIN_VALUE;
    /** 船坞偏移扫描失败后的冷却，避免每 20 tick 全盒扫一遍。 */
    private static long nextYardScanGameTime = Long.MIN_VALUE;

    static {
        VsState.install(VsPilot::observeCore, VsPilot::tryDamage,
                VsPilot::tryRepair, VsPilot::damageExplosion);
        VsState.installGeometryRefresh(VsPilot::refreshPhysicalGeometry);
        // 只注册服务端干燥区：单人存档里客户端和集成服务端同进程，
        // 客户端渲染用的 ShipWaterRenderer 不能被这里覆盖（否则方块船舱内会漏滤镜）。
        OceanEnvironment.setServerDryZone(new OceanEnvironment.DryZone() {
            @Override
            public boolean isDry(Level level, Vec3 pos) {
                return VsPilot.isInsideDryZone(level, pos);
            }

            @Override
            public boolean isInsideCompartment(Level level, Vec3 pos) {
                return VsPilot.isInsideCompartment(level, pos);
            }
        });
    }

    /** 装配刚完成时 VS2 可能还没把这艘船标成"已加载"，推进器先挂在这里，下一 tick 再试。 */
    private static ServerShip pendingShip;
    private static VsThruster pendingThruster;
    private static ServerLevel pendingLevel;

    /**
     * 装配之后还要继续把船标成"动态"的 tick 数。
     *
     * <p>VS2 的 ShipAssembler 建船时先 {@code toShip.isStatic = true}
     * （1.20.1/main 的 ShipAssembler.kt:139），几行之后才在 :172 设回 false。
     * 刚体的类型多半是按当时那个 true 建的，之后改标志位并不重建刚体——
     * 结果就是船在物理世界里是个静态体，重力、推力全不响应。</p>
     */
    private static int unstaticTicks;

    /** 物理船活着的时候每 2 tick 推进一次船内模拟（反应堆 / 氧气 / 水泵 / 压载）。 */
    private static long simTickCounter;

    private VsPilot() {
    }

    // ------------------------------------------------------------------ 查询

    /** 物理船的船状态；没有物理船时返回 null。 */
    @Nullable
    public static ShipState state() {
        return ship == null ? null : state;
    }

    public static boolean hasShip() {
        return ship != null;
    }

    public static VsThruster thruster() {
        return thruster;
    }

    /** 装配时那台声纳的位置。 */
    @Nullable
    public static BlockPos corePos() {
        return corePos;
    }

    /**
     * 刚体质量（kg）。GameTest 用它断言"方块到底有没有全部进刚体"——
     * 我们踩过的坑就是只有声纳一格进去了、质量只剩 300。
     */
    public static double shipMass() {
        if (ship == null) {
            return 0.0D;
        }
        try {
            ServerShip live = lastLevel == null ? ship : view(lastLevel);
            return VsBridge.mass(live);
        } catch (Throwable ignored) {
            return 0.0D;
        }
    }

    /** 刚体世界坐标；没船返回 null。 */
    @Nullable
    public static Vec3 shipPosition() {
        if (ship == null) {
            return null;
        }
        try {
            ServerShip live = lastLevel == null ? ship : view(lastLevel);
            return VsBridge.position(live);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 刚体速度（格/秒）；没船返回 null。 */
    @Nullable
    public static Vec3 shipVelocity() {
        if (ship == null) {
            return null;
        }
        try {
            ServerShip live = lastLevel == null ? ship : view(lastLevel);
            return VsBridge.velocity(live);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 这艘船还在 VS2 的船列表里吗。
     *
     * <p>{@code /vs delete} 之类的操作会把船从船世界里删掉，但我们手里的
     * {@link ServerShip} 句柄不会变 null——它会变成一个"僵尸"句柄，位置、速度、
     * 质量都还读得出来（永远是最后那一帧的值），于是 status 看起来一切正常、船却根本不动。</p>
     */
    private static boolean stillExists(ServerLevel level) {
        if (ship == null) {
            return false;
        }
        if (level == null) {
            return true;
        }
        try {
            org.valkyrienskies.core.internal.world.VsiServerShipWorld world =
                    org.valkyrienskies.mod.common.VSGameUtilsKt.getShipObjectWorld(level);
            return world != null && world.getAllShips().getById(ship.getId()) != null;
        } catch (Throwable ignored) {
            // 查不了就当它还在，宁可让用户看到旧状态，也别把还活着的船清掉
            return true;
        }
    }

    private static void dropStale() {
        // 船没了，交互锚点也得撤掉。
        com.opamb.barotrauma.ship.water.ShipVolumeGuard.deactivate();
                removeProxy(lastLevel);
        ship = null;
        thruster = null;
        state = null;
        corePos = null;
        snapshot = Map.of();
        initialShipBlocks = 0;
        interiorCells = java.util.List.of();
        guardCells = java.util.List.of();
        interiorOffsetX = Integer.MIN_VALUE;
        interiorOffsetY = Integer.MIN_VALUE;
        interiorOffsetZ = Integer.MIN_VALUE;
        assembledPos = Vec3.ZERO;
        unstaticTicks = 0;
        pendingShip = null;
        pendingThruster = null;
        pendingLevel = null;
        yardOffsetX = Integer.MIN_VALUE;
        yardOffsetY = Integer.MIN_VALUE;
        yardOffsetZ = Integer.MIN_VALUE;
        nextYardScanGameTime = Long.MIN_VALUE;
        hullCheckStartGameTime = Long.MIN_VALUE;
        VsState.deactivate();
    }

    /**
     * 服务器生命周期切换时清掉上一世界的物理船静态状态。
     *
     * <p>单机退出世界再新建世界时 JVM 不重启，但 {@link VsPilot} / {@link VsState}
     * 是进程级静态单例；不清理的话，新世界会误以为上一世界的物理船还在，导致
     * {@code StarterSub.ensure()} 跳过初始潜艇，并继续收到旧船 id 的水面包。</p>
     *
     * <p>这里只清内存，不碰任何世界方块：旧世界已经保存并卸载，不能再用它写方块。</p>
     */
    public static void resetForServerLifecycle() {
        boolean hadShip = ship != null || pendingShip != null;
        com.opamb.barotrauma.ship.water.ShipVolumeGuard.deactivate();
        ship = null;
        thruster = null;
        state = null;
        corePos = null;
        snapshot = Map.of();
        initialShipBlocks = 0;
        interiorCells = java.util.List.of();
        guardCells = java.util.List.of();
        waterSyncTimer = 0;
        interiorOffsetX = Integer.MIN_VALUE;
        interiorOffsetY = Integer.MIN_VALUE;
        interiorOffsetZ = Integer.MIN_VALUE;
        sonarAnchor = null;
        proxyPos = null;
        lastLevel = null;
        yardOffsetX = Integer.MIN_VALUE;
        yardOffsetY = Integer.MIN_VALUE;
        yardOffsetZ = Integer.MIN_VALUE;
        nextYardScanGameTime = Long.MIN_VALUE;
        hullCheckStartGameTime = Long.MIN_VALUE;
        pendingShip = null;
        pendingThruster = null;
        pendingLevel = null;
        unstaticTicks = 0;
        simTickCounter = 0;
        waterStateLogCounter = 0;
        lastWaterPacket = ShipWaterStatePacket.empty();
        VsState.deactivate();
        if (hadShip) {
            LOGGER.info("[vs] 服务器生命周期切换：已清理上一世界的物理船静态状态");
        }
    }

    /**
     * 这个方块位置属于当前物理船吗。
     *
     * <p>方块收进刚体后会被搬到船坞区块，但仍然在 tick；{@code ShipCoreBlockEntity}
     * 用它来决定"方块级模拟要不要停手"。</p>
     */
    private static boolean isPhysicalHere(ServerLevel level, BlockPos pos) {
        if (ship == null || level == null || pos == null) {
            return false;
        }
        if (lastLevel != null && lastLevel != level) {
            return false;
        }
        ServerShip managing = VsBridge.managingShip(level, pos);
        return managing != null && managing.getId() == ship.getId();
    }

    /** 这个位置是不是在某个物理船舱的盒子里（不管进没进水）。 */
    public static boolean isInsideCompartment(Level level, Vec3 pos) {
        if (ship == null || state == null || level == null || pos == null) {
            return false;
        }
        float[] boxes = lastWaterPacket.boxes();
        if (boxes.length < 7) {
            return false;
        }
        double lx = pos.x - VsState.offsetX();
        double ly = pos.y - VsState.offsetY();
        double lz = pos.z - VsState.offsetZ();
        for (int i = 0; i + 6 < boxes.length; i += 7) {
            if (lx >= boxes[i] && lx <= boxes[i + 3] + 1.0F
                    && ly >= boxes[i + 1] && ly <= boxes[i + 4] + 1.0F
                    && lz >= boxes[i + 2] && lz <= boxes[i + 5] + 1.0F) {
                return true;
            }
        }
        return false;
    }

    /**
     * 玩家/实体是否在“未进水的船舱”里。
     *
     * <p>服务端用最近一次同步的水面包判断：把世界坐标换算回装配坐标，落在某个舱室
     * bbox 内且高于该舱室水面，就是干燥区。这样破口舱室进水后，水面盖过眼睛就会
     * 自动切回水下状态。</p>
     */
    public static boolean isInsideDryZone(Level level, Vec3 pos) {
        if (ship == null || state == null || level == null || pos == null) {
            return false;
        }
        ShipWaterStatePacket packet = lastWaterPacket;
        float[] boxes = packet.boxes();
        if (boxes.length < 7) {
            return false;
        }
        double lx = pos.x - VsState.offsetX();
        double ly = pos.y - VsState.offsetY();
        double lz = pos.z - VsState.offsetZ();
        for (int i = 0; i + 6 < boxes.length; i += 7) {
            float minX = boxes[i];
            float minY = boxes[i + 1];
            float minZ = boxes[i + 2];
            float maxX = boxes[i + 3] + 1.0F;
            float maxY = boxes[i + 4] + 1.0F;
            float maxZ = boxes[i + 5] + 1.0F;
            float surface = boxes[i + 6];
            boolean dryBox = surface <= minY + 0.01F;
            if (lx >= minX && lx <= maxX
                    && ly >= minY && ly <= maxY
                    && lz >= minZ && lz <= maxZ
                    && (dryBox || ly > surface + 0.01F)) {
                return true;
            }
        }
        return false;
    }

    /** 把船状态推给物理推进器：油门 / 功率 / 压载 / 进水比例。 */
    private static void applyControl(ShipState control) {
        VsThruster target = thruster;
        // 只认"这艘物理船"的状态对象：同世界若还有别的方块船，别把它的油门推给这艘船
        if (target == null || control == null || control != state) {
            return;
        }
        target.setThrottle(control.throttleX(), control.throttleY(), control.throttleZ());
        target.setPower(control.power());
        ShipSimulation.Report report = control.simulation().report();
        target.setBallast(report.ballastPercent(), control.floodRatio());
    }

    // ------------------------------------------------------------------ tick

    /** 每个 server tick 调一次。任何异常都不许把服务器带崩。 */
    public static void tick() {
        if (unstaticTicks > 0 && ship != null && lastLevel != null) {
            unstaticTicks--;
            try {
                LoadedServerShip loaded = VsBridge.loaded(lastLevel, ship);
                if (loaded != null) {
                    // 前 10 tick 强制静态、后 10 tick 放回动态：
                    // 只反复 setStatic(false) 试过没用（字段变了但刚体类型没重建）。
                    // 这里做一个 静态→动态 的翻转，逼 VS2 重新生成刚体体类型。
                    loaded.setStatic(unstaticTicks > 10);
                }
            } catch (Throwable ignored) {
                // 拿不到就下一 tick 再试
            }
        }
        if (pendingShip != null && pendingLevel != null && pendingThruster != null) {
            try {
                VsThruster attached = VsBridge.attach(pendingLevel, pendingShip, pendingThruster);
                if (attached != null) {
                    thruster = attached;
                    ship = pendingShip;
                    activateState(pendingShip, pendingLevel);
                    pendingShip = null;
                    pendingThruster = null;
                    pendingLevel = null;
                }
            } catch (Throwable error) {
                org.slf4j.LoggerFactory.getLogger("barotrauma").warn("物理船推进器挂载失败", error);
                pendingShip = null;
                pendingThruster = null;
                pendingLevel = null;
            }
        }
        if (ship == null) {
            return;
        }
        // 先把刚体的真实位置 / 速度读出来：船内破口进水要按深度算，
        // 后面的位移/自动驾驶也要用同一份快照。
        ServerLevel level = lastLevel;
        Vec3 now = null;
        Vec3 vel = null;
        if (level != null) {
            try {
                ServerShip live = view(level);
                now = VsBridge.position(live);
                vel = VsBridge.velocity(live);
            } catch (Throwable ignored) {
                // 读不到就下一 tick 再试，不能让物理 tick 抛异常
            }
        }
        // 船世界里的方块仍然在 tick，但方块级推进已经停了：船内模拟（反应堆/氧气/水泵）
        // 得由这里接管，否则面板上的温度、氧气会一直冻在装配那一刻。
        // 破口进水同样只能在这里推进——方块船的 serverTick 在物理船上会提前 return，
        // 只在那边调 tickSeaFlood 的话，物理船砸出洞后水永远不涨。
        if (++simTickCounter % 2L == 0L && state != null) {
            try {
                state.simulation().tick(0.1D);
                if (now != null) {
                    state.simulation().tickSeaFlood(0.1D, Math.max(0.0D, -now.y));
                }
                // 还没破块、但伤害 ≥10%/70% 的船壳渗水
                state.tickLeaks(0.1D);
                // 开着的门：两侧水位不一样时按限速慢慢流，不做瞬间均摊
                state.simulation().tickDoorFlow(0.1D);
            } catch (Throwable ignored) {
                // 模拟出错不影响物理
            }
        }
        // 油门 / 功率 / 压载每 tick 同步一次：声纳面板改了状态，物理那边立刻生效
        applyControl(state);
        // 把刚体的真实位置 / 速度抄回船状态：拆船时按位移铺方块，面板上的航速也得是真的
        if (level != null && now != null && vel != null) {
            // 迟滞：船稳定停在小数 .5 附近时，Math.round 会在 N / N+1 之间每 tick 来回跳，
            // 排水体就跟着每 tick 挪一格、旧格回填水——看起来就是"船体来回乱闪"。
            // 只有真的越过 0.62 格才换格，静止时偏移锁死。
            VsState.setOffset(
                    stableOffset(now.x - assembledPos.x, VsState.offsetX()),
                    stableOffset(now.y - assembledPos.y, VsState.offsetY()),
                    stableOffset(now.z - assembledPos.z, VsState.offsetZ()));
            // 空气海域没有真实水要拦截；保留清水调用只为兼容旧存档里可能残留的水。
            clearShipVolumeWater(level, VsState.offsetX(), VsState.offsetY(), VsState.offsetZ(), now);
            moveProxy(level, VsState.offsetX(), VsState.offsetY(), VsState.offsetZ());
            // 船体破损对账：VS2 自己不支持船上破坏，我们靠船坞方块状态兜底
            // （爆炸、命令、其它 mod 把方块弄没了也能发现）。
            if (level.getGameTime() % 20L == 0L) {
                if (yardOffsetX == Integer.MIN_VALUE && level.getGameTime() >= hullCheckStartGameTime) {
                    discoverYardOffset(level);
                }
                refreshHull(level);
            }
            // 和模拟步进（每 2 tick，10 Hz）对齐：水面目标每 100 ms 更新一次，
            // 客户端再按帧插值。原来是 10 tick（2 Hz），水面会一顿一顿。
            if (state != null && ++waterSyncTimer % 2 == 0) {
                sendWaterState(state);
            }
            // 自动驾驶：物理船没有方块形态的 tickMovement 了，这里按目标点算推力再推给刚体
            if (state != null && state.autopilotHold()) {
                state.autopilotStep(now.x, now.y, now.z, vel.x, vel.y, vel.z);
                applyControl(state);
            }
            if (state != null) {
                state.physics().sync(vel.x, vel.y, vel.z);
            }
            // P3 环境威胁：超耐压深度持续压伤船壳（走 HullDamage → 漏水链路）
            if (state != null && now != null
                    && com.opamb.barotrauma.ship.ShipPressure.tick(level, state, -now.y)) {
                sendHullDamageState(state);
                syncAssembledRecord(level);
            }
            // P3 碰撞：高速时对刚体 AABB 内固体方块抽样，命中即扣耐久（温和值）
            if (state != null && vel != null && level.getGameTime() % 10L == 0L
                    && Math.abs(vel.y) + Math.hypot(vel.x, vel.z) > 4.0D) {
                collideDamage(level, now);
            }
            // 持久化：每 5 秒把快照/耐久的变化同步进认领记录（世界重开后靠它接船）。
            if (level.getGameTime() % 100L == 0L) {
                syncAssembledRecord(level);
            }
        }
    }

    /**
     * 整数偏移的迟滞：离当前格还不到 0.62 格时保持不变，避免在 .5 边界来回跳。
     *
     * <p>排水体/舱内排水都按整数偏移执行，偏移一抖就会每 tick 挪一格、旧格回填海水，
     * 视觉上就是"船体来回乱闪"。0.62 比 0.5 多出的余量能吃掉悬浮噪声。</p>
     */
    private static int stableOffset(double delta, int current) {
        int rounded = (int) Math.round(delta);
        if (rounded == current) {
            return current;
        }
        if (Math.abs(delta - current) < 0.62D) {
            return current;
        }
        return rounded;
    }

    /**
     * 船整格移动后，把新位置舱室里的水清成空气。
     *
     * <p>排水体只盖船壳，舱室是真正的空气；船挪到新位置时那片体积原本是海水，
     * 不清的话玩家一进船就是满舱水。只在整数偏移变化时扫一次（几百格）。</p>
     */
    private static void clearShipVolumeWater(ServerLevel level, int dx, int dy, int dz, Vec3 center) {
        if (dx == interiorOffsetX && dy == interiorOffsetY && dz == interiorOffsetZ) {
            return;
        }
        // 先回填船尾：旧拦截格 - 新拦截格 = 被船腾出来的那层海水
        if (interiorOffsetX != Integer.MIN_VALUE) {
            refillVacated(level, interiorOffsetX, interiorOffsetY, interiorOffsetZ, dx, dy, dz, center);
        }
        doClearShipVolumeWater(level, dx, dy, dz);
    }

    /**
     * 流体拦截格 = 当前船体方块（快照）+ 舱室格。
     *
     * <p>不能只用"舱室向外扩 1 圈"：6 邻接扩不到船壳的棱和角，海水会从那些格
     * 钻进来（无头自检实测漏 44 格）。直接用装配快照里每一个船体方块的世界格，
     * 正好覆盖整个船壳；破掉的格子会从快照里移除，海水就只出现在破口那一格。</p>
     */
    private static List<BlockPos> buildGuardCells(Collection<BlockPos> interior) {
        java.util.LinkedHashSet<BlockPos> out = new java.util.LinkedHashSet<>(snapshot.keySet());
        out.addAll(interior);
        return new ArrayList<>(out);
    }

    /**
     * 船整格移动后，把旧拦截格中"新拦截格不再覆盖"的那些格回填成海水。
     *
     * <p>不回填的话，船每走一格就在海里留下船形空气洞——用户看到的就是
     * "路过的地方全变成空气了"。只在船周围确实是水时回填：停在哨站/洞穴的
     * 干空气里时不能凭空造水。</p>
     */
    private static void refillVacated(ServerLevel level, int ox, int oy, int oz,
                                      int nx, int ny, int nz, Vec3 center) {
        if (guardCells.isEmpty() || !isUnderwater(level, center)) {
            return;
        }
        it.unimi.dsi.fastutil.longs.LongOpenHashSet covered =
                new it.unimi.dsi.fastutil.longs.LongOpenHashSet(guardCells.size() * 2);
        for (BlockPos base : guardCells) {
            covered.add(BlockPos.asLong(base.getX() + nx, base.getY() + ny, base.getZ() + nz));
        }
        int changed = 0;
        for (BlockPos base : guardCells) {
            long oldKey = BlockPos.asLong(base.getX() + ox, base.getY() + oy, base.getZ() + oz);
            if (covered.contains(oldKey)) {
                continue;
            }
            BlockPos pos = new BlockPos(base.getX() + ox, base.getY() + oy, base.getZ() + oz);
            if (level.getBlockState(pos).isAir()) {
                // flag 2：不触发邻居更新，避免一片水互相排队 tick
                level.setBlock(pos, Blocks.WATER.defaultBlockState(), 2);
                changed++;
            }
        }
        if (changed > 0) {
            LOGGER.info("[vs] 船尾回填海水：{} 格（偏移 {},{},{} → {},{},{}）",
                    changed, ox, oy, oz, nx, ny, nz);
        }
    }

    /**
     * 空气海域不再通过世界里的水方块判断水下；船尾回填真实水也已停用。
     *
     * <p>保留这个方法名是为了让旧调用点安全地变成 no-op，避免在空气海域里凭空造水。</p>
     */
    private static boolean isUnderwater(ServerLevel level, Vec3 center) {
        return false;
    }

    private static void doClearShipVolumeWater(ServerLevel level, int dx, int dy, int dz) {
        interiorOffsetX = dx;
        interiorOffsetY = dy;
        interiorOffsetZ = dz;
        int changed = 0;
        // 舱室 + 船壳缓冲层都要清。船壳格在物理船上也是空气（方块在船坞），
        // 装配/换格瞬间灌进去的水不清掉，就会在舱底/船壳里留下一滩真实海水；
        // 舱内水位由模拟 + 客户端自绘，真实海水只允许留在船壳更外侧。
        java.util.List<BlockPos> cells = guardCells.isEmpty() ? interiorCells : guardCells;
        for (BlockPos cell : cells) {
            changed += clearWaterAt(level, cell.offset(dx, dy, dz));
        }
        if (changed > 0) {
            LOGGER.info("[vs] 舱内排水：清掉 {} 格（偏移 {},{},{}）", changed, dx, dy, dz);
        }
    }

    private static int clearWaterAt(ServerLevel level, BlockPos target) {
        if (level.getBlockState(target).getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
            level.setBlock(target, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
            return 1;
        }
        return 0;
    }

    // ------------------------------------------------------------------ 船体破损

    /**
     * 核心方块实体在船坞里 tick 时报告自己的真实坐标，这里算出"原坐标 → 船坞坐标"的平移量。
     *
     * <p>VS2 装配后把整艘船平移到远处的船坞区块，船坞里的方块布局和原世界完全一样，
     * 只是整体平移。知道这个平移量，就能用装配快照里的原坐标直接读写船坞方块。</p>
     */
    /**
     * 从船坞区块中心扫出声纳方块，反推"原坐标 → 船坞坐标"的平移量。
     *
     * <p>不能只依赖核心方块实体 tick：船坞区块离玩家很远，服务端不一定给它安排
     * tick（无头自检里就没有）。但 VS2 装配一定把船放在 {@code chunkClaim} 的
     * 中心附近，所以围绕 claim 中心扫一个和船体包围盒同量级的盒子，找唯一的那台
     * 声纳（船脑），就能拿到偏移。</p>
     *
     * <p>先扫中心 ±32 格（覆盖常见潜艇），找不到再按整个建造高度扫一遍。</p>
     */
    private static boolean discoverYardOffset(ServerLevel level) {
        if (yardOffsetX != Integer.MIN_VALUE) {
            return true;
        }
        if (ship == null || corePos == null || snapshot.isEmpty()) {
            return false;
        }
        long now = level.getGameTime();
        if (now < nextYardScanGameTime) {
            return false;
        }
        try {
            ServerShip live = view(level);
            if (live == null) {
                live = ship;
            }
            org.valkyrienskies.core.api.ships.properties.ChunkClaim claim = live.getChunkClaim();
            if (claim == null) {
                return false;
            }
            org.joml.Vector3i center = claim.getCenterBlockCoordinates(
                    org.valkyrienskies.mod.common.VSGameUtilsKt.getYRange(level),
                    new org.joml.Vector3i());
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos pos : snapshot.keySet()) {
                minX = Math.min(minX, pos.getX());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX());
                maxZ = Math.max(maxZ, pos.getZ());
            }
            int radiusXZ = Math.max(maxX - minX, maxZ - minZ) / 2 + 12;
            BlockPos found = findSonar(level, center, radiusXZ, 32);
            if (found == null) {
                found = findSonar(level, center, radiusXZ,
                        Math.max(1, (level.getMaxBuildHeight() - level.getMinBuildHeight()) / 2));
            }
            if (found == null) {
                nextYardScanGameTime = now + 200L;
                LOGGER.warn("[vs] 船坞偏移探测：claim 中心 {} 附近没找到声纳方块", center);
                return false;
            }
            yardOffsetX = found.getX() - corePos.getX();
            yardOffsetY = found.getY() - corePos.getY();
            yardOffsetZ = found.getZ() - corePos.getZ();
            LOGGER.info("[vs] 船坞偏移（扫 claim 中心）：({},{},{})｜声纳 {} → {}｜claim 中心 {}",
                    yardOffsetX, yardOffsetY, yardOffsetZ, corePos, found, center);
            return true;
        } catch (Throwable error) {
            LOGGER.warn("[vs] 船坞偏移探测失败：{}", error.toString());
            return false;
        }
    }

    @Nullable
    private static BlockPos findSonar(ServerLevel level, org.joml.Vector3i center,
                                      int radiusXZ, int radiusY) {
        for (BlockPos pos : BlockPos.betweenClosed(
                center.x - radiusXZ, center.y - radiusY, center.z - radiusXZ,
                center.x + radiusXZ, center.y + radiusY, center.z + radiusXZ)) {
            if (level.getBlockState(pos).is(ModShipBlocks.SONAR.get())) {
                return pos.immutable();
            }
        }
        return null;
    }

    private static void observeCore(ServerLevel level, BlockPos shipyardPos) {
        BlockPos original = corePos;
        if (original == null) {
            return;
        }
        int dx = shipyardPos.getX() - original.getX();
        int dy = shipyardPos.getY() - original.getY();
        int dz = shipyardPos.getZ() - original.getZ();
        if (dx == yardOffsetX && dy == yardOffsetY && dz == yardOffsetZ) {
            return;
        }
        yardOffsetX = dx;
        yardOffsetY = dy;
        yardOffsetZ = dz;
        lastLevel = level;
        LOGGER.info("[vs] 船坞坐标对账：偏移 ({},{},{})｜原核心 {} → 船坞 {}",
                dx, dy, dz, original, shipyardPos);
    }

    /**
     * 每秒对账一次船坞方块和装配快照，发现船体被破坏就重新分舱。
     *
     * <p>物理船上砸方块由 {@link #tryBreak} 直接处理；这里兜的是爆炸、命令、
     * 其它 mod 等旁路修改。破口会让舱室从"密闭"变成"连海"，模拟里开始进水。</p>
     */
    /** 门开合后强制重扫船坞几何；方块类型没变，{@link #refreshHull} 不会触发。 */
    private static void refreshPhysicalGeometry(ServerLevel level) {
        rebuildGeometry(level, List.of());
    }

    /**
     * 船坞坐标 → 装配坐标。物理船的方块实体（排水泵等）报上来的是船坞里的格子，
     * 而船状态里的舱室、破口全是装配坐标。
     */
    public static BlockPos toAssembly(BlockPos yardPos) {
        if (yardPos == null || yardOffsetX == Integer.MIN_VALUE) {
            return yardPos;
        }
        return yardPos.offset(-yardOffsetX, -yardOffsetY, -yardOffsetZ);
    }

    private static void refreshHull(ServerLevel level) {
        ShipState current = state;
        if (current == null || snapshot.isEmpty() || yardOffsetX == Integer.MIN_VALUE) {
            return;
        }
        // 刚装配完先等 1 秒：VS2 搬方块/加载船坞区块有延迟，别把"还没读出来"当成破坏
        if (level.getGameTime() < hullCheckStartGameTime) {
            return;
        }
        List<BlockPos> broken = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> entry : snapshot.entrySet()) {
            BlockPos yard = entry.getKey().offset(yardOffsetX, yardOffsetY, yardOffsetZ);
            BlockState actual = level.getBlockState(yard);
            if (actual.isAir() || !actual.is(entry.getValue().getBlock())) {
                broken.add(entry.getKey());
            }
        }
        if (broken.isEmpty()) {
            return;
        }
        for (BlockPos pos : broken) {
            snapshot.remove(pos);
        }
        rebuildGeometry(level, broken);
    }

    /**
     * 用船坞里的真实方块重新扫描船体几何，并替换当前船状态的舱室/连海空腔。
     *
     * <p>扫描坐标仍用"装配原坐标"，只是读取时加上船坞偏移。这样舱室、破口、
     * 声纳位置都和客户端收到的那套坐标一致，不需要做任何坐标变换。</p>
     */
    private static void rebuildGeometry(ServerLevel level, List<BlockPos> broken) {
        ShipState current = state;
        BlockPos core = corePos;
        if (current == null || core == null || yardOffsetX == Integer.MIN_VALUE) {
            return;
        }
        final int ox = yardOffsetX;
        final int oy = yardOffsetY;
        final int oz = yardOffsetZ;
        ShipGeometry.Probe probe = new ShipGeometry.Probe() {
            @Override
            public boolean isSolid(BlockPos pos) {
                if (pos.equals(core)) {
                    return false;
                }
                return ModShipBlocks.isSolidForShip(level.getBlockState(pos.offset(ox, oy, oz)));
            }

            @Override
            public boolean isWater(BlockPos pos) {
                // 船坞区块里是船体方块本身，没有海水；进水是模拟量
                return false;
            }

            @Override
            public boolean isDoorway(BlockPos pos) {
                return ModShipBlocks.isDoorway(level.getBlockState(pos.offset(ox, oy, oz)));
            }

            @Override
            public boolean isOpenDoorway(BlockPos pos) {
                return ModShipBlocks.isOpenDoorway(level.getBlockState(pos.offset(ox, oy, oz)));
            }
        };
        ShipGeometry.Result geometry = ShipGeometry.scanShip(core, ShipCoreBlockEntity.RADIUS,
                pos -> ModShipBlocks.isShipBlock(level.getBlockState(pos.offset(ox, oy, oz))), probe);
        current.replaceGeometry(geometry);
        // breached 方块仍是 ship block，重新装配/重扫后耐久表可能是空的：按 blockstate 补 0%。
        List<BlockPos> breached = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> entry : snapshot.entrySet()) {
            if (HullBlock.isBreached(entry.getValue())) {
                current.hullDamage().setHealth(entry.getKey(), 0.0F);
                breached.add(entry.getKey());
            }
        }
        // 记录连海破口位置：客户端据此在洞口画水束/气泡。内部隔墙的洞会被过滤掉。
        current.addBreaches(broken);
        current.addBreaches(breached);
        // 洞越大进水越快：已删除格 + 0% breached 格都算开口。
        int destroyed = Math.max(0, initialShipBlocks - snapshot.size());
        current.simulation().setBreachSize(Math.max(1, destroyed + breached.size()));
        // 用"旧的舱内格 + 新的密闭舱室"合并，而不是直接取新 openPockets：
        // 破口舱室会从 compartments 挪到 openPockets，但它的格子在旧集合里，
        // 必须继续挡真实海水；而 openPockets 可能包含船壳外的空气，不能一起保护。
        java.util.LinkedHashSet<BlockPos> merged = new java.util.LinkedHashSet<>(interiorCells);
        for (ShipGeometry.Compartment compartment : geometry.compartments()) {
            merged.addAll(compartment.cells());
        }
        interiorCells = new ArrayList<>(merged);
        guardCells = buildGuardCells(interiorCells);
        // 舱室格变了：让下一 tick 重新清一次新舱室里的水
        interiorOffsetX = Integer.MIN_VALUE;
        interiorOffsetY = Integer.MIN_VALUE;
        interiorOffsetZ = Integer.MIN_VALUE;
        LOGGER.info("[vs] 空气海域（破损后）：舱室 {} 格 + 剩余船体 {} 格 = {} 格（真实水拦截保持停用）",
                interiorCells.size(), snapshot.size(), guardCells.size());
        LOGGER.info("[vs] 船体破损：{} 格被破坏 → 重扫 {} 个舱室 / {} 处连海 / 舱容 {}",
                broken.size(), geometry.compartments().size(), geometry.openPockets().size(),
                geometry.totalVolume());
        if (!broken.isEmpty()) {
            LOGGER.info("[vs] 破口位置（原坐标，前 5 个）：{}",
                    broken.subList(0, Math.min(5, broken.size())));
        }
        for (ShipGeometry.Compartment pocket : geometry.openPockets()) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos pos : pocket.cells()) {
                minX = Math.min(minX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX());
                maxY = Math.max(maxY, pos.getY());
                maxZ = Math.max(maxZ, pos.getZ());
            }
            LOGGER.info("[vs] 连海空腔：{} 格，bbox x{}..{} y{}..{} z{}..{}",
                    pocket.volume(), minX, maxX, minY, maxY, minZ, maxZ);
        }
        sendWaterState(current);
    }

    /**
     * 从玩家眼睛沿视线对装配快照做 AABB 射线检测，返回命中的装配原坐标方块。
     *
     * <p>船在世界里是连续渲染的刚体，方块本体在船坞；必须用完整的
     * {@code worldToShip}（含旋转）把视线变换回装配原坐标，不能只减平移量，
     * 否则船一转身/倾斜，砸的位置就会偏。</p>
     */
    @Nullable
    private static BlockPos pickHullBlock(ServerLevel level, ServerPlayer player) {
        if (ship == null || state == null || snapshot.isEmpty()) {
            return null;
        }
        ServerShip live = view(level);
        if (live == null) {
            return null;
        }
        Vec3 eye = player.getEyePosition();
        // +1 格余量：客户端创造模式的交互距离和属性值可能差一点，
        // 客户端既然发出了破坏包，说明它已经判定命中了。
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(player.getBlockReach() + 1.0D));
        org.joml.Matrix4d worldToShip;
        org.joml.Vector3d origin;
        try {
            worldToShip = new org.joml.Matrix4d(live.getTransform().getShipToWorld()).invert();
            org.joml.Vector3dc positionInShip = live.getTransform().getPositionInShip();
            origin = new org.joml.Vector3d(
                    positionInShip.x() - assembledPos.x,
                    positionInShip.y() - assembledPos.y,
                    positionInShip.z() - assembledPos.z);
        } catch (Throwable error) {
            return null;
        }
        org.joml.Vector3d eyeShip = worldToShip.transformPosition(
                new org.joml.Vector3d(eye.x, eye.y, eye.z), new org.joml.Vector3d()).sub(origin);
        org.joml.Vector3d endShip = worldToShip.transformPosition(
                new org.joml.Vector3d(end.x, end.y, end.z), new org.joml.Vector3d()).sub(origin);
        Vec3 from = new Vec3(eyeShip.x, eyeShip.y, eyeShip.z);
        Vec3 to = new Vec3(endShip.x, endShip.y, endShip.z);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : snapshot.keySet()) {
            java.util.Optional<Vec3> hit = new AABB(pos).clip(from, to);
            if (hit.isEmpty()) {
                continue;
            }
            double distance = hit.get().distanceToSqr(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        if (best == null) {
            return null;
        }
        // 船外还有真实世界方块（地形/哨站）。如果视线先撞到它们，不能隔墙拆/焊船。
        ClipContext clip = new ClipContext(eye, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player);
        BlockHitResult worldHit = RaycastUtilsKt.vanillaClip(level, clip);
        if (worldHit.getType() == HitResult.Type.BLOCK
                && worldHit.getLocation().distanceToSqr(eye) < bestDistance) {
            return null;
        }
        return best;
    }

    /**
     * 物理船左键：先扣船壳耐久；0 时方块切成 breached（无碰撞、保留数据），不删除。
     * {@code ShipBreakMixin} 在服务端收到"开始破坏"时调用。
     */
    private static boolean tryDamage(ServerLevel level, ServerPlayer player, BlockPos packetPos, float amount) {
        if (ship == null || state == null || lastLevel != level || snapshot.isEmpty()
                || player.isSpectator() || amount <= 0.0F) {
            return false;
        }
        BlockPos best = pickHullBlock(level, player);
        if (best == null) {
            return false;
        }
        BlockState block = snapshot.get(best);
        if (block == null) {
            return false;
        }
        if (block.is(ModShipBlocks.SONAR.get())) {
            player.displayClientMessage(Component.translatable("message.barotrauma.hull_sonar")
                    .withStyle(ChatFormatting.GOLD), true);
            return true;
        }
        float max = state.hullMaxHealth(block);
        if (max <= 0.0F) {
            return false;
        }
        // 创造模式蹲下 = 建造模式：0% 的 breached 方块直接清掉，普通方块一击打到 0%。
        if (player.isCreative() && player.isShiftKeyDown() && HullBlock.isBreached(block)) {
            if (yardOffsetX == Integer.MIN_VALUE) {
                discoverYardOffset(level);
            }
            if (yardOffsetX == Integer.MIN_VALUE) {
                return false;
            }
            state.hullDamage().clear(best);
            state.removeBreach(best);
            level.setBlock(best.offset(yardOffsetX, yardOffsetY, yardOffsetZ),
                    Blocks.AIR.defaultBlockState(), 3);
            snapshot.remove(best);
            rebuildGeometry(level, List.of(best));
            state.removeBreach(best);
            sendHullDamageState(state);
            return true;
        }
        if (player.isCreative() && player.isShiftKeyDown()) {
            amount = max * 2.0F;
        }
        float health = state.hullDamage().applyDamage(best, amount, max);
        if (health <= 0.0F) {
            breachSnapshotBlock(level, player, best, block);
        } else {
            Vec3 hitWorld = toWorld(best);
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT,
                    hitWorld.x, hitWorld.y, hitWorld.z,
                    6, 0.25D, 0.25D, 0.25D, 0.02D);
            level.playSound(null, BlockPos.containing(hitWorld),
                    net.minecraft.sounds.SoundEvents.NETHERITE_BLOCK_HIT,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.6F, 1.2F);
            sendHullDamageState(state);
        }
        return true;
    }

    /** 物理船右键焊接：沿视线找一格受损船壳并回耐久。 */
    private static boolean tryRepair(ServerLevel level, ServerPlayer player, float amount) {
        if (ship == null || state == null || lastLevel != level || snapshot.isEmpty()
                || player.isSpectator()) {
            return false;
        }
        if (yardOffsetX == Integer.MIN_VALUE) {
            discoverYardOffset(level);
        }
        if (yardOffsetX == Integer.MIN_VALUE) {
            return false;
        }
        BlockPos best = pickHullBlock(level, player);
        if (best == null) {
            return false;
        }
        BlockState block = snapshot.get(best);
        if (block == null || block.is(ModShipBlocks.SONAR.get())) {
            return false;
        }
        float max = state.hullMaxHealth(block);
        if (max <= 0.0F || !state.hullDamage().isDamaged(best)) {
            return false;
        }
        boolean wasBreached = HullBlock.isBreached(block);
        float health = state.hullDamage().repair(best, amount, max);
        if (level.getGameTime() % 10L == 0L) {
            level.playSound(null, BlockPos.containing(toWorld(best)),
                    net.minecraft.sounds.SoundEvents.FIRE_AMBIENT,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.35F, 1.7F);
        }
        if (wasBreached && health > 0.0F) {
            // 焊过 0%：先撤掉破口记录，再恢复船坞方块碰撞并重扫封口。
            state.removeBreach(best);
            BlockState restored = block.setValue(HullBlock.BREACHED, false);
            level.setBlock(best.offset(yardOffsetX, yardOffsetY, yardOffsetZ), restored, 3);
            snapshot.put(best, restored);
            rebuildGeometry(level, List.of());
        }
        sendHullDamageState(state);
        return true;
    }

    /** 把装配快照里的原坐标映射到船当前的世界坐标。 */
    private static Vec3 toWorld(BlockPos original) {
        if (ship == null) {
            return Vec3.atCenterOf(original);
        }
        org.joml.Vector3dc positionInShip = ship.getTransform().getPositionInShip();
        org.joml.Vector3d shipSpace = new org.joml.Vector3d(
                original.getX() + 0.5D + positionInShip.x() - assembledPos.x,
                original.getY() + 0.5D + positionInShip.y() - assembledPos.y,
                original.getZ() + 0.5D + positionInShip.z() - assembledPos.z);
        org.joml.Vector3d world = ship.getTransform().getShipToWorld()
                .transformPosition(shipSpace, new org.joml.Vector3d());
        return new Vec3(world.x, world.y, world.z);
    }

    /**
     * 耐久到 0：把船坞方块切成 {@code breached}（无碰撞、不可见、保留数据），不删除、不掉落。
     * 修回 >0 时 {@link #tryRepair} 会恢复实体并重扫封口。
     */
    private static void breachSnapshotBlock(ServerLevel level, @Nullable ServerPlayer player,
                                            BlockPos original, BlockState block) {
        if (yardOffsetX == Integer.MIN_VALUE) {
            discoverYardOffset(level);
        }
        if (yardOffsetX == Integer.MIN_VALUE) {
            return;
        }
        if (!(block.getBlock() instanceof HullBlock)) {
            // 兜底：不是船壳类方块就退回原来的删除逻辑（正常不会走到）。
            state.hullDamage().clear(original);
            level.setBlock(original.offset(yardOffsetX, yardOffsetY, yardOffsetZ),
                    Blocks.AIR.defaultBlockState(), 3);
            snapshot.remove(original);
            rebuildGeometry(level, List.of(original));
            return;
        }
        state.hullDamage().setHealth(original, 0.0F);
        BlockState breached = block.setValue(HullBlock.BREACHED, true);
        level.setBlock(original.offset(yardOffsetX, yardOffsetY, yardOffsetZ), breached, 3);
        snapshot.put(original, breached);
        level.playSound(null, BlockPos.containing(toWorld(original)),
                net.minecraft.sounds.SoundEvents.NETHERITE_BLOCK_BREAK,
                net.minecraft.sounds.SoundSource.PLAYERS, 0.8F, 0.9F);
        rebuildGeometry(level, List.of(original));
        if (player != null) {
            player.displayClientMessage(Component.translatable("message.barotrauma.hull_broken")
                    .withStyle(ChatFormatting.RED), true);
        }
        sendHullDamageState(state);
    }

    /**
     * 物理船爆炸：方块在世界里是空气，原版爆炸列表根本碰不到它们。
     * 这里按装配快照逐个映射到世界坐标，按距离衰减扣耐久，最后统一重扫。
     */
    private static int damageExplosion(ServerLevel level, Vec3 center, double radius, float baseAmount) {
        if (ship == null || state == null || lastLevel != level || snapshot.isEmpty()
                || radius <= 0.0D) {
            return 0;
        }
        if (yardOffsetX == Integer.MIN_VALUE) {
            discoverYardOffset(level);
        }
        if (yardOffsetX == Integer.MIN_VALUE) {
            return 0;
        }
        ServerShip live = view(level);
        if (live == null) {
            return 0;
        }
        org.joml.Vector3dc positionInShip = live.getTransform().getPositionInShip();
        org.joml.Vector3d origin = new org.joml.Vector3d(
                positionInShip.x() - assembledPos.x,
                positionInShip.y() - assembledPos.y,
                positionInShip.z() - assembledPos.z);
        org.joml.Matrix4dc shipToWorld = live.getTransform().getShipToWorld();
        org.joml.Vector3d blast = new org.joml.Vector3d(center.x, center.y, center.z);
        List<BlockPos> broken = new ArrayList<>();
        int hit = 0;
        for (Map.Entry<BlockPos, BlockState> entry : snapshot.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState block = entry.getValue();
            if (block.is(ModShipBlocks.SONAR.get())) {
                continue;
            }
            float max = state.hullMaxHealth(block);
            if (max <= 0.0F) {
                continue;
            }
            org.joml.Vector3d shipSpace = new org.joml.Vector3d(
                    pos.getX() + 0.5D + origin.x,
                    pos.getY() + 0.5D + origin.y,
                    pos.getZ() + 0.5D + origin.z);
            org.joml.Vector3d world = shipToWorld.transformPosition(shipSpace, new org.joml.Vector3d());
            double distance = world.distance(blast);
            if (distance > radius) {
                continue;
            }
            float amount = (float) (baseAmount * (1.0D - distance / radius));
            if (amount <= 0.0F) {
                continue;
            }
            hit++;
            if (state.hullDamage().applyDamage(pos, amount, max) <= 0.0F) {
                broken.add(pos);
            }
        }
        List<BlockPos> breached = new ArrayList<>();
        for (BlockPos pos : broken) {
            BlockState block = snapshot.get(pos);
            if (block != null && block.getBlock() instanceof HullBlock) {
                state.hullDamage().setHealth(pos, 0.0F);
                BlockState next = block.setValue(HullBlock.BREACHED, true);
                level.setBlock(pos.offset(yardOffsetX, yardOffsetY, yardOffsetZ), next, 3);
                snapshot.put(pos, next);
                breached.add(pos);
            } else if (block != null) {
                state.hullDamage().clear(pos);
                level.setBlock(pos.offset(yardOffsetX, yardOffsetY, yardOffsetZ),
                        Blocks.AIR.defaultBlockState(), 3);
                snapshot.remove(pos);
            }
        }
        if (!breached.isEmpty()) {
            rebuildGeometry(level, breached);
        }
        if (hit > 0) {
            sendHullDamageState(state);
        }
        return hit;
    }

    /** 自检用：按原坐标在船坞里拆一格并重扫，验证破口进水链路。 */
    public static boolean damageForTest(ServerLevel level, BlockPos originalPos) {
        if (ship == null || state == null) {
            return false;
        }
        if (yardOffsetX == Integer.MIN_VALUE) {
            discoverYardOffset(level);
        }
        if (yardOffsetX == Integer.MIN_VALUE) {
            return false;
        }
        if (!snapshot.containsKey(originalPos)) {
            return false;
        }
        level.setBlock(originalPos.offset(yardOffsetX, yardOffsetY, yardOffsetZ),
                Blocks.AIR.defaultBlockState(), 3);
        snapshot.remove(originalPos);
        rebuildGeometry(level, List.of(originalPos));
        return true;
    }

    /**
     * 交互锚点跟着船整格移动。
     *
     * <p>船上右键不到方块（VS2 2.4.11 限制），所以声纳的交互点用一块真实方块代理。
     * 船挪一格，代理挪一格；旧位置的代理撤掉、新位置补上。</p>
     */
    private static void moveProxy(ServerLevel level, int dx, int dy, int dz) {
        if (sonarAnchor == null) {
            return;
        }
        BlockPos target = sonarAnchor.offset(dx, dy, dz);
        if (target.equals(proxyPos)) {
            return;
        }
        if (proxyPos != null && level.getBlockState(proxyPos).is(ModShipBlocks.SONAR_PROXY.get())) {
            level.setBlock(proxyPos, Blocks.AIR.defaultBlockState(), 2);
        }
        proxyPos = target;
        BlockState existing = level.getBlockState(target);
        if (existing.isAir() || existing.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
            level.setBlock(target, ModShipBlocks.SONAR_PROXY.get().defaultBlockState(), 2);
        }
    }

    /** 撤掉交互锚点（拆船 / 船丢了的时候）。 */
    private static void removeProxy(ServerLevel level) {
        if (level != null && proxyPos != null
                && level.getBlockState(proxyPos).is(ModShipBlocks.SONAR_PROXY.get())) {
            level.setBlock(proxyPos, Blocks.AIR.defaultBlockState(), 2);
        }
        proxyPos = null;
        sonarAnchor = null;
    }

    /** 水面同步日志节流。 */
    private static long waterStateLogCounter;

    /** 把每个舱室的包围盒 + 水面高度发给客户端（自绘水面用）。 */
    private static void sendWaterState(ShipState st) {
        long shipId = ship != null ? ship.getId() : -1L;
        ShipWaterStatePacket packet = com.opamb.barotrauma.ship.ShipWaterStateBuilder.build(st, shipId, true,
                (float) assembledPos.x, (float) assembledPos.y, (float) assembledPos.z);
        lastWaterPacket = packet;
        ModNetwork.sendToAll(packet);
        // 船壳耐久和水平同步：同一套 anchor/坐标空间，客户端用同一个 render 变换映射。
        ModNetwork.sendToAll(ShipHullDamagePacket.build(st, shipId, true,
                (float) assembledPos.x, (float) assembledPos.y, (float) assembledPos.z));
        if (++waterStateLogCounter % 60L == 0L) {
            LOGGER.info("[vs] 水面同步：船 {}｜密闭舱 {}｜连海 {}｜连海水 {} 单位｜进水 {}%",
                    shipId, st.compartments().size(), st.geometry().openPockets().size(),
                    Math.round(st.simulation().pocketWaterTotal()),
                    Math.round(st.floodRatio() * 100.0D));
        }
    }

    /** 受击/焊接后立即同步一次船壳耐久（不等下一个水面包）。 */
    private static void sendHullDamageState(ShipState st) {
        if (st == null) {
            return;
        }
        long shipId = ship != null ? ship.getId() : -1L;
        ModNetwork.sendToAll(ShipHullDamagePacket.build(st, shipId, true,
                (float) assembledPos.x, (float) assembledPos.y, (float) assembledPos.z));
    }

    /** 装船成功后登记到 {@link VsState}，让方块那一套知道要停手。 */
    private static void activateState(ServerShip target, ServerLevel level) {
        if (target == null) {
            return;
        }
        lastLevel = level;
        VsState.activate(corePos, state, VsPilot::isPhysicalHere, VsPilot::applyControl);
    }

    // ------------------------------------------------------------------ 装配

    /** 装配最多试几次。实测同一位置会间歇性只收进一小部分方块，重试一次基本就好。 */
    private static final int ASSEMBLY_ATTEMPTS = 3;

    /**
     * 实际质量达到期望值的这个比例才算装配成功。
     *
     * <p>不放 100%：VS2 算质量走的是它自己的体素/连通性数据，对"被别的方块挡住的边角"
     * 有一点点容差，我们接受 15%。低于这个数就一定是"方块没全进去"。</p>
     */
    private static final double ASSEMBLY_MIN_RATIO = 0.85D;

    /** 把方块收成一艘物理船。返回给玩家看的一行摘要。 */
    public static String launch(ServerLevel level, Collection<BlockPos> blocks) {
        return launch(level, blocks, null, null);
    }

    public static String launch(ServerLevel level, Collection<BlockPos> blocks, ShipState shipState) {
        return launch(level, blocks, shipState, null);
    }

    public static String launch(ServerLevel level, Collection<BlockPos> blocks,
                                @Nullable ShipState shipState, @Nullable BlockPos core) {
        lastLevel = level;
        if (ship != null && !stillExists(level)) {
            dropStale();
        }
        if (ship != null) {
            return "已经有一艘物理船了，先 /barotrauma ship vs dock 拆掉";
        }
        if (blocks.isEmpty()) {
            return "没有船体方块可收";
        }
        // 1) 方块快照必须在装配之前取：装配会把方块从世界里搬走
        Map<BlockPos, BlockState> captured = new LinkedHashMap<>();
        for (BlockPos pos : blocks) {
            BlockState at = level.getBlockState(pos);
            if (!at.isAir()) {
                captured.put(pos.immutable(), at);
            }
        }
        if (captured.isEmpty()) {
            return "没有船体方块可收";
        }
        // 1b) 这艘船"应该"多重：VS2 自己给每个方块定的质量加起来。
        //     装配后必须拿它对账，否则会出现"船壳被删掉、刚体里只有声纳一格"却报成功。
        double expected = 0.0D;
        for (BlockState state : captured.values()) {
            expected += VsBridge.blockMass(state);
        }
        // 2) 把舱内的水方块清掉：水不在装配集合里，留在原地就是一个"水泡"，
        //    船一走原地陷下去一块，还会被当成"船舱进水"
        if (shipState != null) {
            shipState.clearWater(level);
        }
        // 3) 装配 + 对账。装配是"先删方块再铺到船坞区块"，一旦铺失败，
        //    世界里那艘船就直接消失了（用户看到的"船没了、原地变海水、船和声纳不在一体"）。
        //    所以每次装配完都拿质量对账，不对就删掉半成品、把方块按快照铺回原位再试。
        StringBuilder trail = new StringBuilder();
        ServerShip assembled = null;
        double lastMass = 0.0D;
        for (int attempt = 1; attempt <= ASSEMBLY_ATTEMPTS; attempt++) {
            ServerShip candidate;
            try {
                candidate = VsBridge.assemble(level, blocks);
            } catch (Throwable error) {
                if (trail.length() > 0) {
                    trail.append("；");
                }
                trail.append("第").append(attempt).append("次异常 ").append(error);
                continue;
            }
            if (candidate == null) {
                if (trail.length() > 0) {
                    trail.append("；");
                }
                trail.append("第").append(attempt).append("次 VS2 没返回船");
                continue;
            }
            lastMass = VsBridge.mass(candidate);
            double ratio = expected <= 0.0D ? 1.0D : lastMass / expected;
            LOGGER.info("[vs] 装配第 {} 次：送进去 {} 格，期望质量 {}，实际 {}（{}%），{}",
                    attempt, captured.size(), Math.round(expected), Math.round(lastMass),
                    Math.round(ratio * 100.0D), VsBridge.claim(candidate));
            if (ratio >= ASSEMBLY_MIN_RATIO) {
                assembled = candidate;
                break;
            }
            // 只收进一部分：这个刚体不能留（留着就是"幽灵船"），删掉它再把方块铺回原位
            if (trail.length() > 0) {
                trail.append("；");
            }
            trail.append("第").append(attempt).append("次只收进 ").append(Math.round(lastMass))
                    .append("/").append(Math.round(expected)).append(" kg");
            try {
                VsBridge.discard(level, candidate);
            } catch (Throwable error) {
                trail.append("（删不掉：").append(error).append("）");
            }
            restoreBlocks(level, captured, 0, 0, 0);
            if (shipState != null) {
                shipState.clearWater(level);
            }
        }
        if (assembled == null) {
            // 全部失败：方块已经在每次尝试后铺回原位了，这里再兜一次，确保一格不丢
            int back = restoreBlocks(level, captured, 0, 0, 0);
            if (shipState != null) {
                shipState.clearWater(level);
            }
            String msg = "VS2 装配失败（" + trail + "）。已按快照把 " + back
                    + " 格船体放回原位，船没丢、没进水。重试一次 /barotrauma ship vs launch 通常就好了";
            LOGGER.warn("[vs] {}", msg);
            return msg;
        }
        corePos = core;
        snapshot = captured;
        initialShipBlocks = captured.size();
        assembledPos = VsBridge.position(assembled);
        // 4) 排水体：VS2 已经把船体方块从世界挖走了，海里那块空腔会被海水立刻灌满。
        //    用不可见、不碰撞、挡水的方块把**船壳方块**占的位置填死——只盖船壳，
        //    舱室格子保持真正的空气（用户要求：舱内必须能放方块、能交互）。
        //    船整格移动时，新位置的水由 tick 里的 clearShipVolumeWater 清掉。
        interiorCells = new java.util.ArrayList<>();
        if (shipState != null) {
            for (com.opamb.barotrauma.ship.ShipGeometry.Compartment compartment : shipState.compartments()) {
                interiorCells.addAll(compartment.cells());
            }
            // 连海空腔（破口舱室）也算"舱内"：真实海水不许直接灌进来，
            // 否则客户端会同时看到原版水 + 自绘水面，破口就成了瞬满。
            for (com.opamb.barotrauma.ship.ShipGeometry.Compartment pocket : shipState.geometry().openPockets()) {
                interiorCells.addAll(pocket.cells());
            }
        }
        interiorOffsetX = Integer.MIN_VALUE;
        interiorOffsetY = Integer.MIN_VALUE;
        interiorOffsetZ = Integer.MIN_VALUE;
        // 4) 空气海域：不再需要拦截/回填真实水。guardCells 仍保留，用于清掉旧存档里
        //    可能残留的水方块和做诊断；LevelFluidGuardMixin 因为 guard 未激活而失效。
        guardCells = buildGuardCells(interiorCells);
        com.opamb.barotrauma.ship.water.ShipVolumeGuard.deactivate();
        LOGGER.info("[vs] 空气海域：真实水拦截已停用（舱室 {} 格 + 船体 {} 格，保留清水/诊断）",
                interiorCells.size(), snapshot.size());
        // 船坞偏移要等核心方块实体在船坞里 tick 之后才知道；破损对账先等 1 秒
        yardOffsetX = Integer.MIN_VALUE;
        yardOffsetY = Integer.MIN_VALUE;
        yardOffsetZ = Integer.MIN_VALUE;
        nextYardScanGameTime = level.getGameTime();
        hullCheckStartGameTime = level.getGameTime() + 20L;

        // 4b) 交互锚点：VS2 船上右键不到方块，把一块不可见的声纳代理放到声纳原位，
        //     玩家右键它就能打开声纳（跟船整格移动，见 tick / moveProxy）。
        sonarAnchor = null;
        for (Map.Entry<BlockPos, BlockState> entry : captured.entrySet()) {
            if (entry.getValue().is(ModShipBlocks.SONAR.get())) {
                sonarAnchor = entry.getKey().immutable();
                break;
            }
        }
        proxyPos = null;
        if (sonarAnchor != null) {
            proxyPos = sonarAnchor;
            level.setBlock(sonarAnchor, ModShipBlocks.SONAR_PROXY.get().defaultBlockState(), 3);
        }
        // 见 unstaticTicks 的注释：装配出来的船默认是静态体，要连推几 tick 才动得起来
        unstaticTicks = 20;
        VsThruster control = new VsThruster();
        VsThruster attached = null;
        try {
            attached = VsBridge.attach(level, assembled, control);
        } catch (Throwable ignored) {
            // 走下面的 pending 分支，下一 tick 再试
        }
        if (attached == null) {
            pendingShip = assembled;
            pendingThruster = control;
            pendingLevel = level;
            thruster = control;
            ship = assembled;
            state = shipState;
            VsState.activate(core, shipState, VsPilot::isPhysicalHere, VsPilot::applyControl);
            applyControl(shipState);
            sendWaterState(shipState);
            recordAssembled(level, captured, shipState, core, assembled);
            Vec3 pendingPos = VsBridge.position(assembled);
            return String.format(Locale.ROOT,
                    "已收成物理船：%d 格，质量 %.0f/%.0f，中心 (%.1f, %.1f, %.1f)；VS2 尚未加载，推进器下一 tick 补挂",
                    captured.size(), lastMass, expected,
                    pendingPos.x, pendingPos.y, pendingPos.z);
        }
        thruster = attached;
        ship = assembled;
        state = shipState;
        VsState.activate(core, shipState, VsPilot::isPhysicalHere, VsPilot::applyControl);
        applyControl(shipState);
        sendWaterState(shipState);
        recordAssembled(level, captured, shipState, core, assembled);
        Vec3 pos = VsBridge.position(assembled);
        return String.format(Locale.ROOT,
                "已收成物理船：%d 格，质量 %.0f/%.0f，中心 (%.1f, %.1f, %.1f)，%s｜方块级移动/水位已停用，只用刚体",
                captured.size(), lastMass, expected, pos.x, pos.y, pos.z, flags(level, assembled));
    }

    /**
     * 按快照把方块铺回世界（带整数位移），返回真的铺下去的格数。
     *
     * <p>两个地方用：装配失败的回滚（位移 0），和 {@link #dock}（位移 = 船开出去多远）。
     * 空位才铺，被别的方块占着就跳过——绝不覆盖玩家后来放的东西。</p>
     */
    static int restoreBlocks(ServerLevel level, Map<BlockPos, BlockState> snapshot,
                             int dx, int dy, int dz) {
        int placed = 0;
        for (Map.Entry<BlockPos, BlockState> entry : snapshot.entrySet()) {
            BlockPos target = entry.getKey().offset(dx, dy, dz);
            BlockState existing = level.getBlockState(target);
            if (!existing.isAir() && !existing.canBeReplaced()
                    && !existing.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                continue;
            }
            level.setBlock(target, entry.getValue(), 3);
            placed++;
        }
        return placed;
    }

    public static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("barotrauma");

    /** 诊断：船在 VS2 里是不是"已加载/动态"。不动态的船物理不会算。 */
    private static String flags(ServerLevel level, ServerShip target) {
        LoadedServerShip loaded = VsBridge.loaded(level, target);
        if (loaded == null) {
            return "未加载";
        }
        return loaded.isStatic() ? "已加载·静态" : "已加载·动态";
    }

    // ------------------------------------------------------------------ 拆船

    /**
     * 拆回方块。
     *
     * <p>用装配时的方块快照铺回世界，位置 = 原坐标 + 船自装配以来的整数位移。
     * 因为姿态是锁死的（永远平行于海平面，见 {@link VsThruster}），
     * 这里不需要做旋转变换，位移就是纯平移。</p>
     */
    public static String dock(ServerLevel level) {
        if (ship == null) {
            return "现在没有物理船";
        }
        // 方块要铺回它原来那个维度，跨维度拆船会把船铺到错误的世界上
        if (lastLevel != null && lastLevel != level) {
            return "物理船在另一个维度（" + lastLevel.dimension().location() + "），去那边再拆";
        }
        ServerShip live = view(level);
        if (live == null) {
            live = ship;
        }
        Vec3 now = VsBridge.position(live);
        int dx = (int) Math.round(now.x - assembledPos.x);
        int dy = (int) Math.round(now.y - assembledPos.y);
        int dz = (int) Math.round(now.z - assembledPos.z);
        Map<BlockPos, BlockState> restore = snapshot;
        BlockPos oldCore = corePos;

        // 1) 先把刚体从船世界里删掉，但不碰世界方块（VS2 的 deleteShip 参数不是"铺回方块"）
        try {
            VsBridge.discard(level, live);
        } catch (Throwable error) {
            return "拆船失败（VS2 删船异常：" + error + "），船还在，方块没动";
        }
        // 2) 按快照铺回世界。
        com.opamb.barotrauma.ship.water.ShipVolumeGuard.deactivate();
                removeProxy(level);
        int placed = restoreBlocks(level, restore, dx, dy, dz);
        // 3) 拆回来时那片体积早就被海水灌满了，方块铺回会把水封在舱里——
        //    不排掉的话拆回来的就是"满水的沉船"（隔离自检实测舱内 26 格全是水）
        if (state != null) {
            state.clearWater(level, dx, dy, dz);
        }
        int skipped = restore.size() - placed;
        // 4) 新的声纳台重新扫描
        BlockPos newCore = oldCore == null ? null : oldCore.offset(dx, dy, dz);
        if (newCore != null && level.getBlockEntity(newCore) instanceof ShipCoreBlockEntity core) {
            ShipState next = core.rescan(level);
            // 飞行期间积累的耐久记录在装配状态里（快照坐标 + 位移 = 世界坐标）。
            // 覆盖重扫结果：重扫只能从 blockstate 恢复 0%，恢复不了 10%~90% 的
            // 部分损伤和加固倍率，直接铺回会"拆一次船、修好的船壳又漏了"。
            if (state != null) {
                next.hullDamage().retain(pos -> false);
                for (Map.Entry<BlockPos, Float> entry : state.hullDamage().sortedEntries()) {
                    next.hullDamage().setHealth(entry.getKey().offset(dx, dy, dz), entry.getValue());
                }
                next.setHullHealthMultiplier(state.hullHealthMultiplier());
                core.setChanged();
            }
        }
        // 认领记录作废：船已回到方块形态，耐久随核心 NBT 走。
        try {
            ShipSavedData.get(level).clearAssembled();
        } catch (Throwable ignored) {
            // 存档取不到也不能挡拆船
        }
        ship = null;
        thruster = null;
        state = null;
        corePos = null;
        snapshot = Map.of();
        initialShipBlocks = 0;
        interiorCells = java.util.List.of();
        guardCells = java.util.List.of();
        interiorOffsetX = Integer.MIN_VALUE;
        interiorOffsetY = Integer.MIN_VALUE;
        interiorOffsetZ = Integer.MIN_VALUE;
        assembledPos = Vec3.ZERO;
        unstaticTicks = 0;
        pendingShip = null;
        pendingThruster = null;
        pendingLevel = null;
        yardOffsetX = Integer.MIN_VALUE;
        yardOffsetY = Integer.MIN_VALUE;
        yardOffsetZ = Integer.MIN_VALUE;
        nextYardScanGameTime = Long.MIN_VALUE;
        hullCheckStartGameTime = Long.MIN_VALUE;
        VsState.deactivate();
        String where = String.format(Locale.ROOT, "%+d,%+d,%+d", dx, dy, dz);
        return skipped == 0
                ? "已拆回世界：铺回 " + placed + " 格（船位移 " + where + "）"
                : "已拆回世界：铺回 " + placed + " 格，" + skipped + " 格被占用没放下（船位移 " + where + "）";
    }

    // ------------------------------------------------------------------ 认领记录（P1 持久化）

    /**
     * 认领失败后的连贯回滚：清掉本 tick 可能已部分发布的运行时状态，<strong>保留存档记录</strong>。
     *
     * <p>{@link #adoptFromSave} 入口保证 {@code ship == null}；此处若非空，说明
     * {@link #adopt} 已经发布到一半（几何重建成功、{@code sendWaterState} 等尾部
     * 步骤抛了异常）。不回滚的话就是"运行时已接管"的半套状态。
     * {@link #resetForServerLifecycle()} 是纯内存清理，不碰任何世界方块。</p>
     *
     * <p>记录必须保留：VS2 船还在（回滚不删刚体），清了记录它就是永久孤儿。
     * 保留的代价是确定性损坏时每次重启都会重试失败一次——可观测、可人工修复
     * （修数据或 {@code /vs delete}），且绝不产生双份船。</p>
     */
    private static void rollbackPartialAdoption() {
        if (ship != null) {
            resetForServerLifecycle();
        }
        pendingAdoption = null;
    }

    /** 装配成功：把认领记录写进世界存档；世界加载后 P1.2 按它接回物理船。 */
    private static void recordAssembled(ServerLevel level, Map<BlockPos, BlockState> captured,
                                        ShipState shipState, BlockPos core, ServerShip assembled) {
        try {
            Vec3 pos = VsBridge.position(assembled);
            ShipSavedData.get(level).setAssembled(ShipSavedData.AssembledShip.of(
                    assembled.getId(), core, proxyPos, pos, captured, shipState));
        } catch (Throwable error) {
            LOGGER.warn("[vs] 写装配认领记录失败：{}", error.toString());
        }
    }

    /** 每 5 秒把快照/耐久的变化（砸掉的格、修回来的格、扣的耐久）同步进认领记录。 */
    public static void syncAssembledRecord(ServerLevel level) {
        if (ship == null || state == null || corePos == null) {
            return;
        }
        try {
            ShipSavedData data = ShipSavedData.get(level);
            ShipSavedData.AssembledShip record = data.assembled();
            if (record == null) {
                recordAssembled(level, snapshot, state, corePos, ship);
                return;
            }
            record.snapshot.clear();
            for (Map.Entry<BlockPos, BlockState> entry : snapshot.entrySet()) {
                record.snapshot.put(entry.getKey().asLong(), entry.getValue());
            }
            record.hullDamage.copyFrom(state.hullDamage());
            record.hullMultiplier = state.hullHealthMultiplier();
            if (proxyPos != null) {
                record.proxyPos = proxyPos;
            }
            // 位置回退匹配用的"最后已知位置"：只认"已加载"的刚体句柄。
            // view() 的回退句柄（未加载数据船）读到的是陈旧 transform，会把 lastX/Y/Z
            // 写回旧位置；也不能写 record.x/y/z——那是快照坐标空间的装配锚点，动它会
            // 破坏破口的世界→快照映射。
            try {
                LoadedServerShip live = VsBridge.loaded(level, ship);
                if (live != null) {
                    Vec3 now = VsBridge.position(live);
                    record.lastX = now.x;
                    record.lastY = now.y;
                    record.lastZ = now.z;
                }
            } catch (Throwable ignored) {
                // 读不到就保留上一次的已知位置
            }
            data.setDirty();
        } catch (Throwable error) {
            LOGGER.warn("[vs] 同步装配记录失败：{}", error.toString());
        }
    }

    // ------------------------------------------------------------------ 世界加载认领（P1.2）

    /** 待认领记录：世界加载后刚体一时找不到就挂起重试，超时把快照铺回原位。 */
    @Nullable
    private static ShipSavedData.AssembledShip pendingAdoption;
    private static long adoptDeadlineGameTime = Long.MIN_VALUE;
    /** 重试窗口：VS2 船数据是随世界懒加载的，给 30 秒。 */
    private static final long ADOPT_RETRY_TICKS = 600L;
    /** 按 id 找不到时按位置匹配的半径（米）。服务器停机期间船不会动。 */
    private static final double ADOPT_POSITION_TOLERANCE = 16.0D;

    /**
     * 世界加载后按存档记录认领物理船。
     *
     * <p>服务端启动先 {@link #resetForServerLifecycle()} 清空静态，再调用这里：</p>
     * <ol>
     *   <li>按 ship id 找 VS2 刚体；找不到退到"位置最近"匹配；</li>
     *   <li>找到 → {@link #adopt}：从记录重建快照/船状态/推进器/代理方块；</li>
     *   <li>找不到 → 挂起 {@link #pendingAdoption} 重试；超时按丢失处理，
     *       把快照铺回装配位置并清记录，避免留下不可控的野船。</li>
     * </ol>
     */
    public static void adoptFromSave(ServerLevel level) {
        if (!VsSupport.available() || ship != null) {
            return;
        }
        ShipSavedData.AssembledShip record;
        try {
            record = ShipSavedData.get(level).assembled();
        } catch (Throwable error) {
            LOGGER.warn("[vs] 读装配认领记录失败：{}", error.toString());
            return;
        }
        if (record == null) {
            return;
        }
        ServerShip found = VsBridge.ensureShipDataLoaded(level, record.vsShipId);
        if (found == null) {
            // 用"最后已知位置"而不是装配位置：船开出去再停服的话，装配原点早就不是船所在了
            found = VsBridge.nearestShip(level, record.lastX, record.lastY, record.lastZ,
                    ADOPT_POSITION_TOLERANCE);
        }
        if (found != null) {
            // 认领路径抛任何异常都不许带崩启动（这里跑在 ServerStarted 的下一 tick 主线程）：
            // 回滚 + 清记录，降级为无物理船，宁可少一艘船，不要开不起服
            try {
                adopt(level, record, found);
            } catch (Throwable error) {
                LOGGER.warn("[vs] 认领物理船 {} 异常（{}），回滚运行时状态（记录保留，下次重启重试）",
                        record.vsShipId, error.toString());
                rollbackPartialAdoption();
            }
            pendingAdoption = null;
            return;
        }
        if (pendingAdoption == null) {
            pendingAdoption = record;
            adoptDeadlineGameTime = level.getGameTime() + ADOPT_RETRY_TICKS;
            LOGGER.warn("[vs] 有装配记录（shipId={}，快照 {} 格）但 VS2 刚体还没加载，{} 秒内重试",
                    record.vsShipId, record.snapshot.size(), ADOPT_RETRY_TICKS / 20L);
        }
    }

    /** 每秒重试认领；超过期限按"刚体丢失"处理：快照铺回装配位置 + 清记录。 */
    public static void tryAdoptTick(ServerLevel level) {
        if (pendingAdoption == null) {
            return;
        }
        if (ship != null) {
            pendingAdoption = null;
            return;
        }
        ServerShip found = VsBridge.ensureShipDataLoaded(level, pendingAdoption.vsShipId);
        if (found == null) {
            found = VsBridge.nearestShip(level, pendingAdoption.lastX, pendingAdoption.lastY,
                    pendingAdoption.lastZ, ADOPT_POSITION_TOLERANCE);
        }
        if (found != null) {
            // 与 adoptFromSave 相同的兜底：认领异常回滚 + 清记录降级，不带崩 tick 循环
            try {
                adopt(level, pendingAdoption, found);
            } catch (Throwable error) {
                LOGGER.warn("[vs] 认领物理船 {} 异常（{}），回滚运行时状态（记录保留，下次重启重试）",
                        pendingAdoption.vsShipId, error.toString());
                rollbackPartialAdoption();
            }
            pendingAdoption = null;
            return;
        }
        if (level.getGameTime() > adoptDeadlineGameTime) {
            // 铺方块前的终检（三态）：按 id 和按最后已知位置（全量船，含不同 id 的
            // 恢复结果）都确认不存在，才允许铺——查询失败或疑似仍在都继续等。
            // 铺错（双份船）不可逆，拒绝铺（继续等）可逆。
            Boolean gone = VsBridge.shipDefinitelyGone(level, pendingAdoption.vsShipId,
                    pendingAdoption.lastX, pendingAdoption.lastY, pendingAdoption.lastZ,
                    ADOPT_POSITION_TOLERANCE);
            if (gone == null || !gone) {
                adoptDeadlineGameTime = level.getGameTime() + ADOPT_RETRY_TICKS;
                LOGGER.warn("[vs] 超时但 VS2 船数据{}，延长 {} 秒继续等待认领",
                        gone == null ? "查询失败（暂无法判定）" : "仍有疑似存在的船",
                        ADOPT_RETRY_TICKS / 20L);
                return;
            }
            LOGGER.warn("[vs] {} 秒内没等到 VS2 刚体且确认已不存在，按丢失处理",
                    ADOPT_RETRY_TICKS / 20L);
            restoreRecordBlocks(level, pendingAdoption);
            try {
                ShipSavedData.get(level).clearAssembled();
            } catch (Throwable ignored) {
                // 清不掉记录也不影响铺方块
            }
            pendingAdoption = null;
        }
    }

    /**
     * P3 碰撞伤害（退路实现，VS2 2.4.11 没有可订阅的船-地形碰撞回调）：
     * 高速时在刚体 AABB 附近抽样 8 个整数格，命中实心方块就对最近快照格扣 5% 耐久。
     */
    private static void collideDamage(ServerLevel level, Vec3 shipPos) {
        if (ship == null || state == null || snapshot.isEmpty()) {
            return;
        }
        try {
            BlockPos probe = BlockPos.containing(shipPos);
            for (BlockPos offset : SAMPLE_OFFSETS) {
                BlockPos at = probe.offset(offset);
                BlockState solid = level.getBlockState(at);
                if (!solid.isAir() && solid.isSolidRender(level, at)) {
                    BlockPos best = null;
                    double bestDist = Double.MAX_VALUE;
                    for (BlockPos pos : snapshot.keySet()) {
                        double d = pos.distSqr(probe);
                        if (d < bestDist) {
                            bestDist = d;
                            best = pos;
                        }
                    }
                    if (best == null) {
                        return;
                    }
                    BlockState block = snapshot.get(best);
                    float max = state.hullMaxHealth(block);
                    if (max <= 0.0F) {
                        return;
                    }
                    state.hullDamage().applyDamage(best, max * 0.05F, max);
                    level.playSound(null, at, net.minecraft.sounds.SoundEvents.NETHERITE_BLOCK_HIT,
                            net.minecraft.sounds.SoundSource.PLAYERS, 0.8F, 0.8F);
                    sendHullDamageState(state);
                    return;
                }
            }
        } catch (Throwable ignored) {
            // 碰撞检测出错不影响物理
        }
    }

    private static final BlockPos[] SAMPLE_OFFSETS = {
            new BlockPos(3, 0, 0), new BlockPos(-3, 0, 0), new BlockPos(0, 2, 0),
            new BlockPos(0, -2, 0), new BlockPos(0, 0, 3), new BlockPos(0, 0, -3),
            new BlockPos(2, 1, 2), new BlockPos(-2, -1, -2)
    };

    /** 兜底：刚体丢了（存档损坏/外部删船），把快照按装配位置铺回世界，不让船凭空消失。 */
    private static void restoreRecordBlocks(ServerLevel level, ShipSavedData.AssembledShip record) {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        for (Map.Entry<Long, BlockState> entry : record.snapshot.entrySet()) {
            blocks.put(BlockPos.of(entry.getKey()), entry.getValue());
        }
        int placed = restoreBlocks(level, blocks, 0, 0, 0);
        LOGGER.warn("[vs] 丢失的物理船已按快照铺回装配位置：{}/{} 格", placed, blocks.size());
    }

    /** 认领成功：从记录重建全部运行时状态（快照、几何、耐久、推进器、代理、干燥区）。 */
    private static void adopt(ServerLevel level, ShipSavedData.AssembledShip record,
                              ServerShip found) {
        Map<BlockPos, BlockState> snap = new LinkedHashMap<>();
        for (Map.Entry<Long, BlockState> entry : record.snapshot.entrySet()) {
            snap.put(BlockPos.of(entry.getKey()), entry.getValue());
        }
        int sonar = 0;
        for (BlockState blockState : snap.values()) {
            if (blockState.is(ModShipBlocks.SONAR.get())) {
                sonar++;
            }
        }
        BlockPos core = record.corePos;
        // 用快照当探针重建几何：船坞区块此时可能还没加载，绝不能读世界方块。
        ShipGeometry.Probe probe = new ShipGeometry.Probe() {
            @Override
            public boolean isSolid(BlockPos pos) {
                if (pos.equals(core)) {
                    return false;
                }
                BlockState blockState = snap.get(pos);
                return blockState != null && ModShipBlocks.isSolidForShip(blockState);
            }

            @Override
            public boolean isWater(BlockPos pos) {
                return false;
            }

            @Override
            public boolean isDoorway(BlockPos pos) {
                BlockState blockState = snap.get(pos);
                return blockState != null && ModShipBlocks.isDoorway(blockState);
            }

            @Override
            public boolean isOpenDoorway(BlockPos pos) {
                BlockState blockState = snap.get(pos);
                return blockState != null && ModShipBlocks.isOpenDoorway(blockState);
            }
        };
        ShipGeometry.Result geometry = ShipGeometry.scanShip(core, ShipCoreBlockEntity.RADIUS,
                snap::containsKey, probe);
        ShipState rebuilt = new ShipState(geometry,
                new MachineCount(0, 0, 0, 0, 0, 0, sonar));
        rebuilt.setHullHealthMultiplier(record.hullMultiplier);
        rebuilt.hullDamage().copyFrom(record.hullDamage);
        // 注意键类型：snap 是 Map<BlockPos,...>，containsKey(long) 会装箱成 Long 恒为 false，
        // 曾把重建出的耐久/几何全部清空。
        rebuilt.hullDamage().retain(snap::containsKey);
        List<BlockPos> breached = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> entry : snap.entrySet()) {
            if (HullBlock.isBreached(entry.getValue())) {
                rebuilt.hullDamage().setHealth(entry.getKey(), 0.0F);
                breached.add(entry.getKey());
            }
        }
        rebuilt.addBreaches(breached);
        // 破口大小与 live 路径（refreshHull :790）同口径：被整格打掉的格 + 快照里 BREACHED 状态的格。
        // 只数 breached 会漏掉打穿的洞——重启后进水比重启前慢，且此后每次重扫的 destroyed 都从 0 起算。
        // initialBlocks<=0 是旧存档（无该字段），按当前快照大小兜底（与原行为一致）。
        int initial = record.initialBlocks > 0 ? record.initialBlocks : snap.size();
        int destroyed = Math.max(0, initial - snap.size());
        rebuilt.simulation().setBreachSize(Math.max(1, destroyed + breached.size()));

        ship = found;
        state = rebuilt;
        snapshot = snap;
        initialShipBlocks = initial;
        corePos = core;
        sonarAnchor = core;
        proxyPos = record.proxyPos;
        assembledPos = new Vec3(record.x, record.y, record.z);
        lastLevel = level;
        yardOffsetX = Integer.MIN_VALUE;
        yardOffsetY = Integer.MIN_VALUE;
        yardOffsetZ = Integer.MIN_VALUE;
        nextYardScanGameTime = level.getGameTime();
        hullCheckStartGameTime = level.getGameTime() + 100L;
        interiorCells = new ArrayList<>();
        for (ShipGeometry.Compartment compartment : rebuilt.compartments()) {
            interiorCells.addAll(compartment.cells());
        }
        for (ShipGeometry.Compartment pocket : rebuilt.geometry().openPockets()) {
            interiorCells.addAll(pocket.cells());
        }
        guardCells = buildGuardCells(interiorCells);
        interiorOffsetX = Integer.MIN_VALUE;
        interiorOffsetY = Integer.MIN_VALUE;
        interiorOffsetZ = Integer.MIN_VALUE;
        waterSyncTimer = 0;
        // VS2 可能把船恢复成静态体：和装配一样连推 20 tick 逼它回动态。
        unstaticTicks = 20;

        VsThruster control = new VsThruster();
        VsThruster attached = null;
        try {
            attached = VsBridge.attach(level, found, control);
        } catch (Throwable ignored) {
            // 下一 tick 走 pending 分支补挂
        }
        if (attached == null) {
            pendingShip = found;
            pendingThruster = control;
            pendingLevel = level;
            thruster = control;
        } else {
            thruster = attached;
        }
        VsState.activate(core, rebuilt, VsPilot::isPhysicalHere, VsPilot::applyControl);
        applyControl(rebuilt);
        sendWaterState(rebuilt);
        LOGGER.info("[vs] 已认领世界里的物理船：shipId={}，快照 {} 格，破口 {} 处，推进器 {}",
                record.vsShipId, snap.size(), breached.size(), attached != null ? "已挂" : "待挂");
        LOGGER.info("[vs] 认领观测：记录里的耐久条目={}，重建后耐久条目={}",
                record.hullDamage.entries().size(), rebuilt.hullDamage().entries().size());
    }

    /**
     * 读数据一律走"已加载"的句柄。
     *
     * <p>{@code assembleToShip} 返回的只是 {@code ShipData}（未加载快照），它的 transform
     * 不随物理更新，读它永远得到装配那一刻的坐标。</p>
     */
    private static ServerShip view(ServerLevel level) {
        if (ship == null) {
            return null;
        }
        ServerShip loaded = VsBridge.loaded(level, ship);
        return loaded != null ? loaded : ship;
    }

    /** 数一下船中心周围有多少非空气方块——拆船后应该是满的。 */
    private static int countBlocks(ServerLevel level, Vec3 center) {
        int cx = (int) Math.floor(center.x);
        int cy = (int) Math.floor(center.y);
        int cz = (int) Math.floor(center.z);
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(cx - 10, cy - 6, cz - 10, cx + 10, cy + 6, cz + 10)) {
            if (!level.getBlockState(pos).isAir()) {
                count++;
            }
        }
        return count;
    }

    public static String status() {
        ServerLevel level = pendingLevel != null ? pendingLevel : lastLevel;
        if (ship != null && !stillExists(level)) {
            dropStale();
        }
        if (ship == null) {
            return "现在没有物理船｜" + VsBridge.worldStats(level);
        }
        ServerShip live = level == null ? ship : view(level);
        if (live == null) {
            live = ship;
        }
        Vec3 pos = VsBridge.position(live);
        Vec3 vel = VsBridge.velocity(live);
        String flags;
        try {
            flags = live.isStatic() ? "静态" : "动态";
        } catch (Throwable error) {
            flags = "未知";
        }
        int dx = (int) Math.round(pos.x - assembledPos.x);
        int dy = (int) Math.round(pos.y - assembledPos.y);
        int dz = (int) Math.round(pos.z - assembledPos.z);
        return String.format(Locale.ROOT,
                "物理船｜质量 %.0f｜中心 (%.1f, %.1f, %.1f)｜速度 (%.2f, %.2f, %.2f) = %.2f 格/秒"
                        + "｜推力实测 (%.0f, %.0f, %.0f) N｜%s｜%s｜%s"
                        + "｜位移 (%+d, %+d, %+d)｜快照 %d 格｜姿态 %s"
                        + "｜physTick=%d 唤醒=%d 直压=%d",
                VsBridge.mass(live), pos.x, pos.y, pos.z, vel.x, vel.y, vel.z, vel.length(),
                thruster == null ? 0 : thruster.lastForceX(),
                thruster == null ? 0 : thruster.lastForceY(),
                thruster == null ? 0 : thruster.lastForceZ(),
                flags, VsBridge.physicsEntity(level, live), VsThruster.probe(),
                dx, dy, dz, snapshot.size(), VsThruster.attitude(),
                VsThruster.calls(), VsThruster.wakes(), VsThruster.directCalls());
    }

    // ------------------------------------------------------------------ 进水诊断

    /**
     * 诊断"装配之后原地是不是变成水坑了"——也就是用户说的"进水"。
     *
     * <p>VS2 装配会把船体方块从世界里挖走，世界里那一块就成了空腔；潜艇在海里，
     * 四周的海水立刻灌进来。透过透明船壳看过去，就是"船里全是水"。
     * 这个方法把船的舱室格子 / 船体格子各数一遍，看它们现在是水、空气还是别的方块。
     * 装配刚完成时应该全空气；过 2 秒再数一次，"水"的格数就是灌进来的海水量。</p>
     */
    public static String floodProbe(ServerLevel level, @Nullable ShipState probe) {
        if (probe == null) {
            return "进水诊断：没有船状态";
        }
        java.util.List<BlockPos> rooms = new java.util.ArrayList<>();
        for (com.opamb.barotrauma.ship.ShipGeometry.Compartment compartment : probe.compartments()) {
            for (BlockPos pos : compartment.cells()) {
                rooms.add(pos);
            }
        }
        int[] room = countStates(level, rooms);
        int[] hull = countStates(level, probe.geometry().hull());
        return String.format(Locale.ROOT,
                "进水诊断：舱室格 %d（水=%d 空气=%d 别的=%d）｜船体格 %d（水=%d 空气=%d 别的=%d）",
                room[0], room[1], room[2], room[3], hull[0], hull[1], hull[2], hull[3]);
    }

    /** 数一批格子里水 / 空气 / 其它各多少，返回 {总数, 水, 空气, 其它}。 */
    private static int[] countStates(ServerLevel level, java.util.Collection<BlockPos> cells) {
        int water = 0;
        int air = 0;
        int other = 0;
        for (BlockPos pos : cells) {
            BlockState state = level.getBlockState(pos);
            if (state.is(net.minecraft.world.level.block.Blocks.WATER)) {
                water++;
            } else if (state.isAir()) {
                air++;
            } else {
                other++;
            }
        }
        return new int[]{cells.size(), water, air, other};
    }

    // ------------------------------------------------------------------ 自检
    /** 自检：装船 + 给推力，等真实 tick 跑完再看速度。 */
    public static String beginSmoke(ServerLevel level, Collection<BlockPos> blocks) {
        return beginSmoke(level, blocks, null, null);
    }

    public static String beginSmoke(ServerLevel level, Collection<BlockPos> blocks,
                                    @Nullable ShipState shipState, @Nullable BlockPos core) {
        // 取样必须在装配之前：装配完方块就不在世界里了
        String sample = sampleBlock(level, blocks);
        String water = waterAt(level, blocks);
        String result = launch(level, blocks, shipState, core);
        if (thruster != null) {
            thruster.setPower(100);
            thruster.setThrottle(0, 0, 1);
        }
        return result + " | 推力 +Z 全速 | " + sample + " | " + water
                + " | 装配后立即｜" + floodProbe(level, shipState);
    }

    /** 船体周围是水还是空气——决定它到底该不该有浮力。 */
    private static String waterAt(ServerLevel level, Collection<BlockPos> blocks) {
        int water = 0;
        int air = 0;
        for (BlockPos pos : blocks) {
            BlockPos outside = pos.above();
            BlockState state = level.getBlockState(outside);
            if (state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                water++;
            } else if (state.isAir()) {
                air++;
            }
        }
        return "周边 水=" + water + " 空气=" + air;
    }

    /**
     * 抽样看 VS2 认不认我们的方块。
     *
     * <p>自定义方块如果没被任何 {@code BlockStateInfoProvider} 认领，VS2 会把它当成空气：
     * 船就没有碰撞体、也不排水——表现就是"穿模往下掉"。</p>
     */
    private static String sampleBlock(ServerLevel level, Collection<BlockPos> blocks) {
        for (BlockPos pos : blocks) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            try {
                kotlin.Pair<Double, org.valkyrienskies.core.internal.world.chunks.VsiBlockType> info =
                        org.valkyrienskies.mod.common.BlockStateInfo.INSTANCE.get(state);
                if (info == null) {
                    return state.getBlock() + " 在 VS2 里没有物理属性";
                }
                return state.getBlock() + " mass=" + info.getFirst()
                        + " type=" + (info.getSecond() == null ? "null" : info.getSecond().toInt());
            } catch (Throwable error) {
                return "取样失败：" + error;
            }
        }
        return "取样：船体方块已不在世界";
    }

    public static String finishSmoke(ServerLevel level) {
        if (ship == null) {
            return "物理船自检：没有船（装配失败）";
        }
        ServerShip live = view(level);
        if (live == null) {
            live = ship;
        }
        Vec3 pos = VsBridge.position(live);
        Vec3 vel = VsBridge.velocity(live);
        double mass = VsBridge.mass(live);
        String line = String.format(Locale.ROOT,
                "物理船 10s 后｜质量 %.0f｜中心 (%.1f, %.1f, %.1f)｜速度 %.2f 格/秒（%.2f, %.2f, %.2f）",
                mass, pos.x, pos.y, pos.z, vel.length(), vel.x, vel.y, vel.z);
        line += "｜" + VsBridge.diagnose(level, ship) + "｜推进器=" + (thruster != null)
                + "｜物理回调次数=" + VsThruster.calls() + "｜" + VsThruster.probe();
        // 装配之后过了 10 秒，原地灌了多少海水——这就是用户看到的"船里全是水"
        line += "｜10s 后｜" + floodProbe(level, state) + "｜" + VsBridge.claim(live);
        String docked = dock(level);
        int placed = countBlocks(level, pos);
        return line + " | " + docked + "，拆回后方块数=" + placed;
    }
}
