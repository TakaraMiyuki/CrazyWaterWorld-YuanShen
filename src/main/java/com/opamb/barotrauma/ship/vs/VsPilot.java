package com.opamb.barotrauma.ship.vs;

import com.opamb.barotrauma.blockentity.ShipCoreBlockEntity;
import com.opamb.barotrauma.network.ModNetwork;
import com.opamb.barotrauma.network.ShipWaterStatePacket;
import com.opamb.barotrauma.registry.ModShipBlocks;
import com.opamb.barotrauma.ship.ShipGeometry;
import com.opamb.barotrauma.ship.ShipSimulation;
import com.opamb.barotrauma.ship.ShipState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
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
        VsState.install(VsPilot::observeCore, VsPilot::tryBreak);
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
        // 船没了，排水体/交互锚点也得撤掉，否则世界里留一层看不见的方块壳
        if (HullPlug.active()) {
            HullPlug.remove(lastLevel);
        }
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
        // 传 null：只清 HullPlug 的静态形状/记录，不对旧世界执行 setBlock
        HullPlug.remove(null);
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
            // 流体禁区跟着船整格挪（不铺方块，只更新舱室拦截范围）
            com.opamb.barotrauma.ship.water.ShipVolumeGuard.setOffset(
                    VsState.offsetX(), VsState.offsetY(), VsState.offsetZ());
            // 船挪到新位置时，新舱室格原本是海水，不清就会"一开船瞬间灌满"。
            // 只在整数偏移变化时清一次；不再每 10 tick 强制清（那会跟玩家的桶/水泵打架）。
            // 清完新位置后，把船尾腾出来的旧格回填海水，避免开过的地方留一条空气隧道。
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
            if (state != null && ++waterSyncTimer % 10 == 0) {
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
     * 船周围是不是海水（不是哨站/洞穴里的空气）。
     *
     * <p>按船体包围盒在水平方向取 8 个点，水比空气多就算水下。
     * 判定错最坏的后果是：在干洞里回填几格水，或者在水下少回填几格——
     * 都不会影响舱内模拟。</p>
     */
    private static boolean isUnderwater(ServerLevel level, Vec3 center) {
        if (snapshot.isEmpty()) {
            return false;
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : snapshot.keySet()) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        double cx = (minX + maxX) / 2.0D + (center.x - assembledPos.x);
        double cy = (minY + maxY) / 2.0D + (center.y - assembledPos.y);
        double cz = (minZ + maxZ) / 2.0D + (center.z - assembledPos.z);
        int dx = (maxX - minX) / 2 + 4;
        int dz = (maxZ - minZ) / 2 + 4;
        int[][] offsets = {
                {dx, 0}, {-dx, 0}, {0, dz}, {0, -dz},
                {dx, dz}, {dx, -dz}, {-dx, dz}, {-dx, -dz}
        };
        int water = 0;
        int air = 0;
        for (int[] offset : offsets) {
            BlockPos pos = BlockPos.containing(cx + offset[0], cy, cz + offset[1]);
            var state = level.getBlockState(pos);
            if (state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                water++;
            } else if (state.isAir()) {
                air++;
            }
        }
        return water > air;
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
        };
        ShipGeometry.Result geometry = ShipGeometry.scanShip(core, ShipCoreBlockEntity.RADIUS,
                pos -> ModShipBlocks.isShipBlock(level.getBlockState(pos.offset(ox, oy, oz))), probe);
        current.replaceGeometry(geometry);
        // 洞越大进水越快：被砸掉的格数 = 初始船体数 - 当前快照数
        current.simulation().setBreachSize(Math.max(1, initialShipBlocks - snapshot.size()));
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
        com.opamb.barotrauma.ship.water.ShipVolumeGuard.setCells(guardCells);
        LOGGER.info("[vs] 流体拦截（破损后）：舱室 {} 格 + 剩余船体 {} 格 = {} 格",
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
     * 物理船砸方块：玩家看到的是连续渲染的船壳，世界里那一格其实没有方块。
     *
     * <p>所以不能依赖原版的 {@code destroyBlock}。这里从玩家眼睛沿视线对装配快照里的
     * 船体方块做一次 AABB 射线检测（带船当前的小数位移），命中后把船坞里对应的方块
     * 删掉，再重扫几何。{@code ShipBreakMixin} 在服务端收到"开始破坏"时调用。</p>
     */
    private static boolean tryBreak(ServerLevel level, ServerPlayer player, BlockPos packetPos) {
        if (ship == null || state == null || lastLevel != level || snapshot.isEmpty()) {
            return false;
        }
        if (yardOffsetX == Integer.MIN_VALUE) {
            discoverYardOffset(level);
        }
        if (yardOffsetX == Integer.MIN_VALUE || player.isSpectator()) {
            return false;
        }
        Vec3 now = shipPosition();
        if (now == null) {
            return false;
        }
        double dx = now.x - assembledPos.x;
        double dy = now.y - assembledPos.y;
        double dz = now.z - assembledPos.z;
        Vec3 eye = player.getEyePosition();
        // +1 格余量：客户端创造模式的交互距离和属性值可能差一点，
        // 客户端既然发出了破坏包，说明它已经判定命中了。
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(player.getBlockReach() + 1.0D));
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Map.Entry<BlockPos, BlockState> entry : snapshot.entrySet()) {
            BlockPos pos = entry.getKey();
            AABB box = new AABB(
                    pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz,
                    pos.getX() + 1.0D + dx, pos.getY() + 1.0D + dy, pos.getZ() + 1.0D + dz);
            java.util.Optional<Vec3> hit = box.clip(eye, end);
            if (hit.isEmpty()) {
                continue;
            }
            double distance = hit.get().distanceToSqr(eye);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        if (best == null) {
            return false;
        }
        // 船外还有真实世界方块（地形/哨站）。如果视线先撞到它们，不能隔墙拆船。
        ClipContext clip = new ClipContext(eye, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player);
        BlockHitResult worldHit = RaycastUtilsKt.vanillaClip(level, clip);
        if (worldHit.getType() == HitResult.Type.BLOCK
                && worldHit.getLocation().distanceToSqr(eye) < bestDistance) {
            return false;
        }
        BlockState removed = snapshot.get(best);
        if (removed == null) {
            return false;
        }
        if (removed.is(ModShipBlocks.SONAR.get())) {
            player.displayClientMessage(Component.literal("声纳是船脑，不能拆")
                    .withStyle(ChatFormatting.GOLD), true);
            return true;
        }
        level.setBlock(best.offset(yardOffsetX, yardOffsetY, yardOffsetZ),
                Blocks.AIR.defaultBlockState(), 3);
        snapshot.remove(best);
        BlockPos dropPos = BlockPos.containing(best.getX() + 0.5D + dx,
                best.getY() + 0.5D + dy, best.getZ() + 0.5D + dz);
        if (!player.isCreative()) {
            net.minecraft.world.level.block.Block.popResource(level, dropPos,
                    new ItemStack(removed.getBlock()));
        }
        // 世界方块是空气，但 levelEvent 仍然会按传入的方块状态播放破坏粒子和声音
        level.levelEvent(2001, dropPos, net.minecraft.world.level.block.Block.getId(removed));
        rebuildGeometry(level, List.of(best));
        player.displayClientMessage(Component.literal("船体破损！").withStyle(ChatFormatting.RED), true);
        return true;
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
        ModNetwork.sendToAll(com.opamb.barotrauma.ship.ShipWaterStateBuilder.build(st, shipId, true,
                (float) assembledPos.x, (float) assembledPos.y, (float) assembledPos.z));
        if (++waterStateLogCounter % 60L == 0L) {
            LOGGER.info("[vs] 水面同步：船 {}｜密闭舱 {}｜连海 {}｜连海水 {} 单位｜进水 {}%",
                    shipId, st.compartments().size(), st.geometry().openPockets().size(),
                    Math.round(st.simulation().pocketWaterTotal()),
                    Math.round(st.floodRatio() * 100.0D));
        }
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
        // 4) 流体禁区：不再铺任何排水方块——直接在 Level#setBlock 里拒绝把水写进"舱室格"。
        //    只保护舱室，不保护船壳格：船壳格让海水自然填满，透过破口看出去不会有一圈干空气。
        //    先清掉旧版本可能留下的排水体（旧存档）。
        HullPlug.remove(level);
        guardCells = buildGuardCells(interiorCells);
        com.opamb.barotrauma.ship.water.ShipVolumeGuard.activate(level, guardCells);
        LOGGER.info("[vs] 流体拦截：舱室 {} 格 + 船体 {} 格 = {} 格",
                interiorCells.size(), snapshot.size(), guardCells.size());
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
        Vec3 pos = VsBridge.position(assembled);
        return String.format(Locale.ROOT,
                "已收成物理船：%d 格，质量 %.0f/%.0f，中心 (%.1f, %.1f, %.1f)，%s｜%s｜方块级移动/水位已停用，只用刚体",
                captured.size(), lastMass, expected, pos.x, pos.y, pos.z, flags(level, assembled),
                HullPlug.status());
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
        // 2) 按快照铺回世界。铺之前必须先撤排水体，否则它把那一片占着、方块铺不进去
        HullPlug.remove(level);
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
            core.rescan(level);
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
                        + "｜physTick=%d 唤醒=%d 直压=%d｜%s",
                VsBridge.mass(live), pos.x, pos.y, pos.z, vel.x, vel.y, vel.z, vel.length(),
                thruster == null ? 0 : thruster.lastForceX(),
                thruster == null ? 0 : thruster.lastForceY(),
                thruster == null ? 0 : thruster.lastForceZ(),
                flags, VsBridge.physicsEntity(level, live), VsThruster.probe(),
                dx, dy, dz, snapshot.size(), VsThruster.attitude(),
                VsThruster.calls(), VsThruster.wakes(), VsThruster.directCalls(),
                HullPlug.status());
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
