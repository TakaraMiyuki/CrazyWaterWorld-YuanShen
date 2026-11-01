package com.opamb.barotrauma.ship.vs;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.core.api.ships.ShipPhysicsListener;
import org.valkyrienskies.core.internal.world.VsiServerShipWorld;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;
import org.valkyrienskies.mod.common.assembly.ShipAssembler;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * VS2 的薄封装：只管"装船 / 拆船 / 读变换"，不掺业务。
 *
 * <p><b>这个类只能在 VS2 已加载时碰</b>——它的方法签名里有 VS2 的类型。调用方必须先问
 * {@link VsSupport#available()}，否则没装 VS2 的整合包会在类加载阶段就炸。</p>
 */
public final class VsBridge {

    private VsBridge() {
    }

    /** 把一批方块收成一艘 VS2 刚体船。返回的船在世界的中心由 VS2 自己算。 */
    public static ServerShip assemble(ServerLevel level, Collection<BlockPos> blocks) {
        Set<BlockPos> set = new LinkedHashSet<>(blocks);
        return ShipAssembler.assembleToShip(level, set, 1.0D);
    }

    /** 拆船：方块按船当前的位置、朝向铺回世界。返回铺回去的方块数。 */
    public static int dock(ServerLevel level, ServerShip ship) {
        // dropBlocks = false 才是"铺回方块"；true 会把整艘船变成掉落物
        return ShipAssembler.INSTANCE.deleteShip(level, ship, false, false);
    }

    public static Vec3 position(ServerShip ship) {
        Vector3dc p = ship.getTransform().getPositionInWorld();
        return new Vec3(p.x(), p.y(), p.z());
    }

    public static Vec3 velocity(ServerShip ship) {
        Vector3dc v = ship.getVelocity();
        return new Vec3(v.x(), v.y(), v.z());
    }

    public static double mass(ServerShip ship) {
        return ship.getInertiaData().getShipMass();
    }

    /**
     * 取"已加载"的船。
     *
     * <p>{@code assembleToShip} 返回的只是 {@code ServerShip} 句柄；能挂附件、能被物理回调的是
     * {@link LoadedServerShip}。装配刚完成时按位置查还不一定能命中（船的区块claim 还没生效），
     * 所以按 id 在 {@code getLoadedShips()} 里查，查不到再退回位置查询。</p>
     */
    public static LoadedServerShip loaded(ServerLevel level, ServerShip ship) {
        if (ship instanceof LoadedServerShip direct) {
            return direct;
        }
        try {
            VsiServerShipWorld world = VSGameUtilsKt.getShipObjectWorld(level);
            if (world != null) {
                LoadedServerShip byId = world.getLoadedShips().getById(ship.getId());
                if (byId != null) {
                    return byId;
                }
            }
        } catch (Throwable ignored) {
            // 退回到位置查询
        }
        try {
            Vector3dc center = ship.getTransform().getPositionInWorld();
            Object found = VSGameUtilsKt.getLoadedShipManagingPos(level, center);
            return found instanceof LoadedServerShip loaded ? loaded : null;
        } catch (Throwable error) {
            return null;
        }
    }

    /**
     * 挂推进器。
     *
     * <p>VS2 的附件是<strong>按类型注册</strong>的：不先 {@code registerAttachment} 就
     * {@code setAttachment} 会抛 "unregistered class"。注册一次即可，存的是运行时状态，
     * 用 transient 序列化（不写存档）。</p>
     */
    public static VsThruster attach(ServerLevel level, ServerShip ship, VsThruster thruster) {
        ensureRegistered();
        // 先直接挂在装配返回的句柄上：VS2 的附件存储是共用的，早一个 tick 挂上，
        // 物理实体构建 listener 列表时才不会漏掉我们
        attachToHandle(ship, thruster);
        LoadedServerShip loaded = loaded(level, ship);
        if (loaded == null) {
            return null;
        }
        loaded.setAttachment(thruster);
        return loaded.getAttachment(VsThruster.class);
    }

    /**
     * 对 {@code ServerShip} 句柄直接挂附件。
     *
     * <p>{@code ServerShip#saveAttachment} 在字节码里是编译器合成的桥方法，源码里写不出来
     * （javac 会报 "cannot find symbol"），只能反射调。</p>
     */
    private static void attachToHandle(ServerShip ship, VsThruster thruster) {
        try {
            java.lang.reflect.Method method = ship.getClass()
                    .getMethod("saveAttachment", Class.class, Object.class);
            method.invoke(ship, VsThruster.class, thruster);
        } catch (Throwable ignored) {
            // 拿不到就走 loaded 那条路
        }
    }

    private static boolean registered;

    private static void ensureRegistered() {
        if (registered) {
            return;
        }
        registered = true;
        try {
            ValkyrienSkiesMod.getVsCore().registerAttachment(VsThruster.class);
        } catch (Throwable ignored) {
            // 已经注册过（例如重载）就忽略
        }
    }

    /** 诊断：这艘船在 VS2 船世界里到底算不算"已加载"。 */
    public static String diagnose(ServerLevel level, ServerShip ship) {
        try {
            VsiServerShipWorld world = VSGameUtilsKt.getShipObjectWorld(level);
            if (world == null) {
                return "船世界为空";
            }
            return "allShips=" + world.getAllShips().size()
                    + " loadedShips=" + world.getLoadedShips().size()
                    + " byId=" + (world.getLoadedShips().getById(ship.getId()) != null)
                    + " 句柄类型=" + ship.getClass().getSimpleName();
        } catch (Throwable error) {
            return "诊断失败：" + error;
        }
    }

    public static VsThruster thruster(ServerLevel level, ServerShip ship) {
        LoadedServerShip loaded = loaded(level, ship);
        return loaded == null ? null : loaded.getAttachment(VsThruster.class);
    }
}
