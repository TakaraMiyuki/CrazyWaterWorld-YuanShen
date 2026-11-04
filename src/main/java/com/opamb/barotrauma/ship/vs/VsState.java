package com.opamb.barotrauma.ship.vs;

import com.opamb.barotrauma.ship.ShipState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * "这艘船现在是不是 VS2 刚体"的<strong>无 VS2 依赖</strong>中转站。
 *
 * <p>为什么需要它：判断这件事的地方（{@code ShipCoreBlockEntity.serverTick}、
 * {@code ShipActionPacket}、{@code StarterSub}）绝大多数<strong>没装 VS2 也要能跑</strong>。
 * 如果它们直接引用 {@link VsPilot}，类加载时就会因为 {@code ServerShip} 不存在而
 * {@code NoClassDefFoundError}。所以这里只放基础类型 + 两个回调接口，
 * 真正的 VS2 逻辑由 {@link VsPilot} 在装船时注册进来。</p>
 *
 * <p>另外它还是"移动系统只能有一套"的唯一开关：物理船活着的时候，
 * 方块那一套（{@code ShipMover} / {@code applyWater} / 破口重扫）必须整段停掉，
 * 否则就是用户看到的"方块移动和物理移动叠在一起"。</p>
 */
public final class VsState {

    /** 问"这个方块位置属于不在的那艘物理船吗"。由 {@link VsPilot} 提供。 */
    public interface Probe {
        boolean isPhysical(ServerLevel level, BlockPos pos);
    }

    /** 把船状态（油门 / 功率 / 压载 / 进水）推给物理推进器。由 {@link VsPilot} 提供。 */
    public interface Control {
        void apply(ShipState ship);
    }

    /**
     * 物理船核心方块实体每 tick 报一次它在船坞里的真实坐标。
     *
     * <p>VS2 装配后把船体方块整体平移到一个远处的"船坞"区块。只有从方块实体
     * 那里拿到这个平移量，才能把"原世界坐标的船体快照"和船坞里真正的方块对上——
     * 破坏方块、船体破损重扫都要靠它。</p>
     */
    public interface CoreObserver {
        void observe(ServerLevel level, BlockPos shipyardPos);
    }

    /** 物理船上砸方块：把玩家看到的世界坐标映射成船坞方块并扣耐久。由 {@link VsPilot} 提供。 */
    public interface Damager {
        boolean tryDamage(ServerLevel level, ServerPlayer player, BlockPos pos, float amount);
    }

    /** 物理船上焊接：沿玩家视线找到船壳格并回耐久。由 {@link VsPilot} 提供。 */
    public interface Repairer {
        boolean tryRepair(ServerLevel level, ServerPlayer player, float amount);
    }

    /** 物理船爆炸：按世界坐标对装配快照里的格子扣耐久。由 {@link VsPilot} 提供。 */
    public interface ExplosionDamager {
        int damageExplosion(ServerLevel level, net.minecraft.world.phys.Vec3 center,
                            double radius, float baseAmount);
    }

    /** 物理船的方块状态变了（例如开门），但方块类型没变，需要重新扫一遍船坞几何。 */
    public interface GeometryRefresh {
        void refresh(ServerLevel level);
    }

    private static volatile boolean active;
    private static volatile Probe probe;
    private static volatile Control control;
    private static volatile CoreObserver coreObserver;
    private static volatile Damager damager;
    private static volatile Repairer repairer;
    private static volatile ExplosionDamager explosionDamager;
    private static volatile GeometryRefresh geometryRefresh;
    private static volatile BlockPos corePos;
    private static volatile ShipState activeState;
    /** 船中心相对装配时刻的整数偏移（格）——拆船时按它把方块铺回世界。 */
    private static volatile int offsetX;
    private static volatile int offsetY;
    private static volatile int offsetZ;

    private VsState() {
    }

    /** 有没有一艘已经被收成 VS2 刚体的船。 */
    public static boolean active() {
        return active;
    }

    /**
     * 这个方块位置是不是"属于物理船"。
     *
     * <p>方块被收进刚体之后仍然留在世界数据里（在船坞区块），所以它还会 tick；
     * 这时候方块级的推进 / 水位 / 破口重扫必须停手，交给物理那一边。</p>
     */
    public static boolean isPhysical(ServerLevel level, BlockPos pos) {
        if (!active || level == null || pos == null) {
            return false;
        }
        Probe current = probe;
        if (current == null) {
            return false;
        }
        try {
            return current.isPhysical(level, pos);
        } catch (Throwable ignored) {
            // 查不了就当作不是，宁可跑方块那一套也别把船卡死
            return false;
        }
    }

    /** 把船状态同步给物理推进器（没装 VS2 / 没有物理船时是空操作）。 */
    public static void syncControl(@Nullable ShipState ship) {
        if (!active || ship == null) {
            return;
        }
        Control current = control;
        if (current == null) {
            return;
        }
        try {
            current.apply(ship);
        } catch (Throwable ignored) {
            // 同步失败不该影响游戏
        }
    }

    /**
     * 注册物理船回调。{@link VsPilot} 类初始化时调一次。
     *
     * <p>这两个回调只在真正的 VS2 逻辑里注册；没装 VS2 时它们一直是 null，
     * 方块实体和 mixin 走到这里都是空操作，不会触发 VS2 类加载。</p>
     */
    public static void install(CoreObserver observer, Damager newDamager,
                               Repairer newRepairer, ExplosionDamager newExplosionDamager) {
        coreObserver = observer;
        damager = newDamager;
        repairer = newRepairer;
        explosionDamager = newExplosionDamager;
    }

    /** 注册"船体方块状态变了，重扫物理船几何"的回调。由 {@link VsPilot} 初始化时调一次。 */
    public static void installGeometryRefresh(GeometryRefresh refresh) {
        geometryRefresh = refresh;
    }

    /**
     * 物理船上开门/关门后调用。方块类型没变，{@code refreshHull} 不会把它当成破损，
     * 所以必须显式重扫：打开的门格要进入舱室，客户端干区才会把门口算进船内。
     */
    public static void refreshGeometry(ServerLevel level) {
        GeometryRefresh current = geometryRefresh;
        if (!active || current == null || level == null) {
            return;
        }
        try {
            current.refresh(level);
        } catch (Throwable ignored) {
            // 重扫失败不能把开门的玩家卡死；下一次 tick 还会继续跑
        }
    }

    /** 物理船核心方块实体在船坞里 tick 时调用。 */
    public static void observePhysicalCore(ServerLevel level, BlockPos shipyardPos) {
        CoreObserver observer = coreObserver;
        if (observer == null) {
            return;
        }
        try {
            observer.observe(level, shipyardPos);
        } catch (Throwable ignored) {
            // 坐标对账失败不影响物理
        }
    }

    /** 服务器收到"开始破坏方块"时调用；返回 true 表示这一下由物理船接管。 */
    public static boolean tryDamage(ServerLevel level, ServerPlayer player, BlockPos pos, float amount) {
        Damager current = damager;
        if (!active || current == null || level == null || player == null || pos == null) {
            return false;
        }
        try {
            return current.tryDamage(level, player, pos, amount);
        } catch (Throwable ignored) {
            // 映射失败就交回原版，别把玩家卡住
            return false;
        }
    }

    /** 焊接工具每 0.1 s 调一次；返回 true 表示确实修到了船壳。 */
    public static boolean tryRepair(ServerLevel level, ServerPlayer player, float amount) {
        Repairer current = repairer;
        if (!active || current == null || level == null || player == null) {
            return false;
        }
        try {
            return current.tryRepair(level, player, amount);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 爆炸事件调一次；物理船的方块在世界里是空气，只能按快照算。 */
    public static int damageExplosion(ServerLevel level, net.minecraft.world.phys.Vec3 center,
                                      double radius, float baseAmount) {
        ExplosionDamager current = explosionDamager;
        if (!active || current == null || level == null || center == null) {
            return 0;
        }
        try {
            return current.damageExplosion(level, center, radius, baseAmount);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static void activate(BlockPos core, @Nullable ShipState state, Probe newProbe, Control newControl) {
        corePos = core;
        activeState = state;
        probe = newProbe;
        control = newControl;
        offsetX = 0;
        offsetY = 0;
        offsetZ = 0;
        active = true;
    }

    /**
     * 物理船那唯一一份船状态。
     *
     * <p>船进刚体之后，世界里那台声纳的方块实体是一个"新"的（旧的在装配时被搬走了），
     * 它自己的 {@code state} 是 null。如果让它按船坞坐标重新扫描，得到的是船坞里的几何——
     * 全是垃圾。所以物理船的声纳方块实体统一代理到这份状态上。</p>
     */
    @Nullable
    public static ShipState activeState() {
        return active ? activeState : null;
    }

    public static void deactivate() {
        active = false;
        probe = null;
        control = null;
        // 注意：coreObserver / damager / repairer / explosionDamager 是 VsPilot 类初始化时
        // 装一次的回调，deactivate 不能清掉——否则同一进程里第二次 launch 后没人接破坏/焊接。
        corePos = null;
        activeState = null;
        offsetX = 0;
        offsetY = 0;
        offsetZ = 0;
    }

    public static void setOffset(int dx, int dy, int dz) {
        offsetX = dx;
        offsetY = dy;
        offsetZ = dz;
    }

    public static int offsetX() {
        return offsetX;
    }

    public static int offsetY() {
        return offsetY;
    }

    public static int offsetZ() {
        return offsetZ;
    }

    /** 装配时那台声纳（船脑）的世界坐标。 */
    @Nullable
    public static BlockPos corePos() {
        return corePos;
    }
}
