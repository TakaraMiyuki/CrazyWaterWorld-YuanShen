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

    /**
     * 把船从 VS2 船世界里删掉，<strong>不碰世界方块</strong>。
     *
     * <p>注意 {@code deleteShip(level, ship, deleteBlocks, dropBlocks)} 的第三个参数不是
     * "铺回方块"：<code>deleteBlocks=false</code> 是"世界一格不动，只删船对象"，
     * <code>true</code> 是把船的世界包围盒**挖成空气**（{@code /vs delete <ship> true} 那个）。
     * VS2 里根本没有"把刚体还原成方块"的 API —— 所以还原方块是我们自己用装配快照做的，
     * 见 {@code VsPilot.dock}。</p>
     */
    public static void discard(ServerLevel level, ServerShip ship) {
        ShipAssembler.INSTANCE.deleteShip(level, ship, false, false);
    }

    /**
     * 这个方块位置归哪艘 VS2 船管（不在任何船里返回 null）。
     *
     * <p>方块进了刚体之后并没有从世界上消失，而是被搬到船坞区块里——所以
     * "这个方块是不是物理船的一部分"可以直接问 VS2 的船坞归属，不用自己记坐标。</p>
     */
    public static ServerShip managingShip(ServerLevel level, BlockPos pos) {
        try {
            org.valkyrienskies.core.api.ships.Ship found =
                    VSGameUtilsKt.getShipManagingPos(level, pos);
            return found instanceof ServerShip server ? server : null;
        } catch (Throwable ignored) {
            // 没装 VS2 / 版本对不上都当作"不在船里"
            return null;
        }
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
     * 船坞区块坐标：装配时 VS2 把船的方块搬到哪去了。
     *
     * <p>VS2 不是把船存在别的维度，而是存在<b>同一个维度的预留远处区块</b>里
     * （{@code chunkClaim} 指向的那一片）。知道这个坐标才能判断"方块到底搬没搬过去"。</p>
     */
    public static String claim(ServerShip ship) {
        try {
            org.valkyrienskies.core.api.ships.properties.ChunkClaim claim = ship.getChunkClaim();
            if (claim == null) {
                return "船坞=无";
            }
            return String.format(java.util.Locale.ROOT, "船坞=chunk[%d..%d, %d..%d] 中心(%d,%d) 维度=%s",
                    claim.getXStart(), claim.getXEnd(), claim.getZStart(), claim.getZEnd(),
                    claim.getXMiddle(), claim.getZMiddle(), ship.getChunkClaimDimension());
        } catch (Throwable error) {
            return "船坞=?（" + error + "）";
        }
    }

    /**
     * 这个方块状态在 VS2 里的质量（kg）；VS2 不认这个方块返回 0。
     *
     * <p>装配前用它算出"这艘船应该有多重"，装配后跟真实质量对比——
     * 这是判断"方块到底有没有全部进刚体"的唯一可靠标准。</p>
     */
    public static double blockMass(net.minecraft.world.level.block.state.BlockState state) {
        try {
            kotlin.Pair<Double, org.valkyrienskies.core.internal.world.chunks.VsiBlockType> info =
                    org.valkyrienskies.mod.common.BlockStateInfo.INSTANCE.get(state);
            return info == null ? 0.0D : info.getFirst();
        } catch (Throwable ignored) {
            return 0.0D;
        }
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

    /**
     * 这艘船在服务端物理世界里到底有没有 {@code PhysicsEntityServer}。
     *
     * <p>这是"刚体进没进求解器"的直接证据。船存在、附件挂上了、{@code physTick} 每秒
     * 回调 60 次、力也算得出来——这些全都可能是真的，但只要对应位置没有物理实体，
     * 重力和力就作用不到任何东西上，表现就是速度恒为 0、位置一格不动。</p>
     */
    public static String physicsEntity(ServerLevel level, ServerShip ship) {
        try {
            VsiServerShipWorld world = VSGameUtilsKt.getShipObjectWorld(level);
            if (world == null) {
                return "物理实体=?（船世界为空）";
            }
            java.util.Map<Long, ?> entities = world.retrieveLoadedPhysicsEntities();
            boolean present = entities.containsKey(Long.valueOf(ship.getId()));
            return "物理实体=" + (present ? "有" : "无") + "（总数 " + entities.size() + "）";
        } catch (Throwable error) {
            return "物理实体=?（查询失败：" + error + "）";
        }
    }

    /**
     * 全局统计：这个维度的船世界里，船、已加载船、物理实体各有多少。
     *
     * <p>没有物理实体 = 这艘船根本没进求解器，重力和推力都作用不到任何东西上。
     * 没有物理船时也要能查，所以单独开一个不依赖 {@link ServerShip} 的入口。</p>
     */
    public static String worldStats(ServerLevel level) {
        if (level == null) {
            return "船世界=未记录维度";
        }
        try {
            VsiServerShipWorld world = VSGameUtilsKt.getShipObjectWorld(level);
            if (world == null) {
                return "船世界=空";
            }
            int entities = 0;
            try {
                entities = world.retrieveLoadedPhysicsEntities().size();
            } catch (Throwable ignored) {
                entities = -1;
            }
            return "船世界：all=" + world.getAllShips().size()
                    + " loaded=" + world.getLoadedShips().size()
                    + " 物理实体=" + entities;
        } catch (Throwable error) {
            return "船世界查询失败：" + error;
        }
    }

    public static VsThruster thruster(ServerLevel level, ServerShip ship) {
        LoadedServerShip loaded = loaded(level, ship);
        return loaded == null ? null : loaded.getAttachment(VsThruster.class);
    }

    // ------------------------------------------------------------------ 认领（P1.2）

    /**
     * 按 id 找已加载的刚体；找不到返回 null。
     *
     * <p>世界刚加载时 VS2 可能还没把船放进 loaded 集合（懒加载），调用方要容忍
     * 暂时为空并重试，不能一次找不到就判定船丢了。</p>
     */
    public static ServerShip shipById(ServerLevel level, long id) {
        try {
            VsiServerShipWorld world = VSGameUtilsKt.getShipObjectWorld(level);
            return world == null ? null : world.getLoadedShips().getById(id);
        } catch (Throwable error) {
            return null;
        }
    }

    /**
     * 认领路径专用：按 id 从**存档数据**里找船（不管物理加载没有），找到就顺手把
     * 船坞区块 sync 加载出来（VS2 靠区块加载事件把船放进 loaded 集合）。
     *
     * <p>返回值：null = 存档里也没有这条 id（真丢了）；非 null = 数据存在，
     * 可能已经加载（直接可用），也可能刚把船坞区块拉起来、下一 tick 才进 loaded。</p>
     */
    public static ServerShip ensureShipDataLoaded(ServerLevel level, long id) {
        try {
            VsiServerShipWorld world = VSGameUtilsKt.getShipObjectWorld(level);
            if (world == null) {
                return null;
            }
            ServerShip loaded = world.getLoadedShips().getById(id);
            if (loaded != null) {
                return loaded;
            }
            ServerShip data = world.getAllShips().getById(id);
            if (data == null) {
                return null;
            }
            var claim = data.getChunkClaim();
            if (claim != null) {
                int cx = claim.getXMiddle() >> 4;
                int cz = claim.getZMiddle() >> 4;
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        level.getChunk(cx + dx, cz + dz);
                    }
                }
            }
            return data;
        } catch (Throwable error) {
            return null;
        }
    }

    /** 位置最近的刚体（id 跨存档不稳定时的退路）；maxDistance 米内没有返回 null。 */
    public static ServerShip nearestShip(ServerLevel level, double x, double y, double z,
                                         double maxDistance) {
        try {
            VsiServerShipWorld world = VSGameUtilsKt.getShipObjectWorld(level);
            if (world == null) {
                return null;
            }
            ServerShip best = null;
            double bestDist = maxDistance * maxDistance;
            for (ServerShip candidate : world.getLoadedShips()) {
                Vector3dc p = candidate.getTransform().getPositionInWorld();
                double dist = (p.x() - x) * (p.x() - x)
                        + (p.y() - y) * (p.y() - y) + (p.z() - z) * (p.z() - z);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = candidate;
                }
            }
            return best;
        } catch (Throwable error) {
            return null;
        }
    }
}
