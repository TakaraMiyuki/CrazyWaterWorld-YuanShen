package com.opamb.barotrauma.ship.vs;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.valkyrienskies.core.api.ships.ServerShip;

import java.util.Collection;
import java.util.Locale;

/**
 * 物理船（VS2）的运行时：装配、推进、拆回方块。
 *
 * <p>这是"物理化"的第一步，只接管<strong>运动</strong>：装船之后整艘船由 VS2 当刚体算，
 * 引擎推力经由 {@link VsThruster} 施加。分舱、水位、机器交互还是老的方块那一套，
 * 但那些方块此时已经不在世界里了，所以这一版<strong>装船期间机器和声纳台都用不了</strong>——
 * 换算到船本地坐标是下一步的事（见 PLAN.md §10.26）。</p>
 */
public final class VsPilot {

    private static ServerShip ship;
    private static VsThruster thruster;
    /**
     * 装船那一刻的船状态。
     *
     * <p>方块被收进刚体之后，世界里的船核心就没了，{@code ShipCores.resolve} 找不到它，
     * 声纳台和操舵包就会当成"没有船"——表现就是界面能开、但开不动船。
     * 所以这里留一份引用，作为找不到核心时的回退。</p>
     */
    private static com.opamb.barotrauma.ship.ShipState state;
    /** 装配刚完成时 VS2 可能还没把这艘船标成"已加载"，推进器先挂在这里，下一 tick 再试。 */
    private static ServerShip pendingShip;
    private static VsThruster pendingThruster;
    private static ServerLevel pendingLevel;

    private VsPilot() {
    }

    /** 物理船的船状态；没有物理船时返回 null。 */
    public static com.opamb.barotrauma.ship.ShipState state() {
        return ship == null ? null : state;
    }

    /** 每个 server tick 调一次：补挂上次没挂上的推进器。任何异常都不许把服务器带崩。 */
    public static void tick() {
        if (pendingShip == null || pendingLevel == null || pendingThruster == null) {
            return;
        }
        try {
            VsThruster attached = VsBridge.attach(pendingLevel, pendingShip, pendingThruster);
            if (attached != null) {
                thruster = attached;
                ship = pendingShip;
                pendingShip = null;
                pendingThruster = null;
                pendingLevel = null;
            }
        } catch (Throwable error) {
            // 挂不上就下个 tick 再试；VS2 版本差异不该让服务器崩掉
            org.slf4j.LoggerFactory.getLogger("barotrauma").warn("物理船推进器挂载失败", error);
            pendingShip = null;
            pendingThruster = null;
            pendingLevel = null;
        }
    }

    public static boolean hasShip() {
        return ship != null;
    }

    public static VsThruster thruster() {
        return thruster;
    }

    // ------------------------------------------------------------------ 装配

    /** 把方块收成一艘物理船。返回给玩家看的一行摘要。 */
    public static String launch(ServerLevel level, Collection<BlockPos> blocks) {
        return launch(level, blocks, null);
    }

    public static String launch(ServerLevel level, Collection<BlockPos> blocks,
                                com.opamb.barotrauma.ship.ShipState shipState) {
        lastLevel = level;
        if (ship != null) {
            return "已经有一艘物理船了，先 dock 拆掉";
        }
        if (blocks.isEmpty()) {
            return "没有船体方块可收";
        }
        ServerShip assembled = VsBridge.assemble(level, blocks);
        if (assembled == null) {
            return "VS2 装配失败（方块集可能是空的或已被占用）";
        }
        VsThruster control = new VsThruster();
        VsThruster attached = null;
        try {
            attached = VsBridge.attach(level, assembled, control);
        } catch (Throwable ignored) {
            // 走下面的 pending 分支，下一 tick 再试
        }
        if (attached == null) {
            // VS2 还没把船标成已加载：先记下来，server tick 里继续试
            pendingShip = assembled;
            pendingThruster = control;
            pendingLevel = level;
            thruster = control;
            ship = assembled;
            state = shipState;
            Vec3 pendingPos = VsBridge.position(assembled);
            return String.format(Locale.ROOT,
                    "已收成物理船：%d 格，质量 %.0f，中心 (%.1f, %.1f, %.1f)；VS2 尚未加载，推进器下一 tick 补挂",
                    blocks.size(), VsBridge.mass(assembled), pendingPos.x, pendingPos.y, pendingPos.z);
        }
        thruster = attached;
        ship = assembled;
        state = shipState;
        Vec3 pos = VsBridge.position(assembled);
        return String.format(Locale.ROOT, "已收成物理船：%d 格，质量 %.0f，中心 (%.1f, %.1f, %.1f)，%s",
                blocks.size(), VsBridge.mass(assembled), pos.x, pos.y, pos.z, flags(level, assembled));
    }

    /** 诊断：船在 VS2 里是不是"已加载/动态"。不动态的船物理不会算。 */
    private static String flags(ServerLevel level, ServerShip target) {
        org.valkyrienskies.core.api.ships.LoadedServerShip loaded = VsBridge.loaded(level, target);
        if (loaded == null) {
            return "未加载";
        }
        return loaded.isStatic() ? "已加载·静态" : "已加载·动态";
    }

    /** 拆回方块。 */
    public static String dock(ServerLevel level) {
        if (ship == null) {
            return "现在没有物理船";
        }
        int placed = VsBridge.dock(level, ship);
        ship = null;
        thruster = null;
        state = null;
        return "已拆回世界：铺回 " + placed + " 格";
    }

    /**
     * 读数据一律走"已加载"的句柄。
     *
     * <p>{@code assembleToShip} 返回的是 {@code ShipData}（未加载快照），它的 transform 不随物理更新，
     * 读它永远得到装配那一刻的坐标。真正的运行态在 {@code LoadedServerShip} 上。</p>
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
        if (ship == null) {
            return "现在没有物理船";
        }
        ServerShip live = (pendingLevel != null ? pendingLevel : lastLevel) == null
                ? ship : view(pendingLevel != null ? pendingLevel : lastLevel);
        if (live == null) {
            live = ship;
        }
        Vec3 pos = VsBridge.position(live);
        Vec3 vel = VsBridge.velocity(live);
        return String.format(Locale.ROOT,
                "物理船｜质量 %.0f｜中心 (%.1f, %.1f, %.1f)｜速度 (%.2f, %.2f, %.2f) = %.2f 格/秒｜推力实测 (%.0f, %.0f, %.0f) N",
                VsBridge.mass(live), pos.x, pos.y, pos.z, vel.x, vel.y, vel.z, vel.length(),
                thruster == null ? 0 : thruster.lastForceX(),
                thruster == null ? 0 : thruster.lastForceY(),
                thruster == null ? 0 : thruster.lastForceZ());
    }

    /** 上一次 launch 用的维度，status 时需要它来查已加载的船。 */
    private static ServerLevel lastLevel;

    // ------------------------------------------------------------------ 自检

    /** 自检：装船 + 给推力，等真实 tick 跑完再看速度。 */
    public static String beginSmoke(ServerLevel level, Collection<BlockPos> blocks) {
        // 取样必须在装配之前：装配完方块就不在世界里了（上一版写反了，永远取不到）
        String sample = sampleBlock(level, blocks);
        String water = waterAt(level, blocks);
        String result = launch(level, blocks);
        if (thruster != null) {
            thruster.setPower(100);
            thruster.setThrottle(0, 0, 1);
        }
        return result + " | 推力 +Z 全速 | " + sample + " | " + water;
    }

    /** 船体周围是水还是空气——决定它到底该不该有浮力。 */
    private static String waterAt(ServerLevel level, Collection<BlockPos> blocks) {
        int water = 0;
        int air = 0;
        for (BlockPos pos : blocks) {
            BlockPos outside = pos.above();
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(outside);
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
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
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
        String docked = dock(level);
        int placed = countBlocks(level, pos);
        return line + " | " + docked + "，拆回后方块数=" + placed;
    }
}
