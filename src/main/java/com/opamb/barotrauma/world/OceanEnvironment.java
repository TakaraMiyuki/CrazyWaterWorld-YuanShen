package com.opamb.barotrauma.world;

import com.opamb.barotrauma.blockentity.ShipCoreBlockEntity;
import com.opamb.barotrauma.ship.ShipCores;
import com.opamb.barotrauma.ship.ShipState;
import com.opamb.barotrauma.ship.vs.VsState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 空气海域的“是否在水下”判定。
 *
 * <p>海里不再有真实水方块，所以不能靠 {@code FluidState} 判断。这里用三层规则：</p>
 * <ol>
 *   <li>必须在深海维度的水柱高度范围内（y = -1..-1000）；</li>
 *   <li>哨站内部是干燥区；</li>
 *   <li>未进水的船舱是干燥区，由服务端/客户端各自注册的 {@link DryZone} 判断。</li>
 * </ol>
 *
 * <p>这个类必须能在服务端和客户端同时加载，不能引用任何客户端专用类。</p>
 */
public final class OceanEnvironment {

    /** 船内干燥区判定。服务端用 VsPilot，客户端用 ShipWaterRenderer。 */
    public interface DryZone {
        boolean isDry(Level level, Vec3 pos);

        /**
         * 这个位置是不是在某艘船的舱室里（不管有没有进水）。
         *
         * <p>船舱进水后必须按"浸没"处理——哪怕船正停在哨站干燥区里。
         * 所以先看这个，再决定要不要套用哨站的干燥判断。</p>
         */
        default boolean isInsideCompartment(Level level, Vec3 pos) {
            return false;
        }
    }

    // 客户端和集成服务端共用同一个 JVM（单人存档），必须分开存：
    // 只留一份 volatile 的话，服务端启动时注册的 VsPilot 会覆盖客户端注册的
    // ShipWaterRenderer，方块船还没装成刚体时 VsPilot 无法判定舱内，滤镜就会漏进舱里。
    private static volatile DryZone clientDryZone;
    private static volatile DryZone serverDryZone;

    private OceanEnvironment() {
    }

    /** 客户端（渲染/滤镜）用的干燥区；由客户端初始化时注册。 */
    public static void setClientDryZone(DryZone zone) {
        clientDryZone = zone;
    }

    /** 服务端（氧气/生存）用的干燥区；由物理船模块注册。 */
    public static void setServerDryZone(DryZone zone) {
        serverDryZone = zone;
    }

    /** 这个位置是否位于深海的水柱体积内（不代表一定浸没，还要排除干燥区）。 */
    public static boolean isInOcean(Level level, Vec3 pos) {
        return level != null && pos != null
                && level.dimension().equals(ModDimensions.THE_DEEP)
                && pos.y <= ModDimensions.WATER_TOP_Y
                && pos.y >= ModDimensions.WATER_BOTTOM_Y;
    }

    /** 哨站内部固定是干燥的；门外平台不算。 */
    public static boolean isInOutpostDryZone(Vec3 pos) {
        if (pos == null) {
            return false;
        }
        if (Math.abs(pos.x) <= OutpostBuilder.HALF_X
                && Math.abs(pos.z) <= OutpostBuilder.HALF_Z
                && pos.y >= OutpostBuilder.MIN_Y
                && pos.y <= OutpostBuilder.MAX_Y) {
            return true;
        }
        // B 中继站（任务交付点）的内部干燥区
        BlockPos block = BlockPos.containing(pos);
        return PoiNetwork.isInsideOutpostBDryZone(block);
    }

    /**
     * 初始潜艇“方块船形态”的固定干燥区。
     *
     * <p>方块船的 {@code ShipWaterStatePacket} 在部分时序下还没到客户端时，滤镜会把舱内
     * 误判成水下；这艘船的位置是写死的，直接用内腔包围盒兜底。装配成物理船后会走
     * {@link #dryZone} 的实时舱室判断。</p>
     */
    public static boolean isInsideStarterSubDryZone(Vec3 pos) {
        if (pos == null) {
            return false;
        }
        return pos.x > StarterSub.ORIGIN_X && pos.x < StarterSub.ORIGIN_X + StarterSub.SIZE_X - 1
                // 上界留半格余量：舱内跳跃顶点眼睛约 -68.1，屋顶表面在 -67；
                // 用 -67.5 既不会在跳跃时闪滤镜，也不会把贴着屋顶游泳的人算成干燥。
                && pos.y >= StarterSub.ORIGIN_Y + 1
                && pos.y < StarterSub.ORIGIN_Y + StarterSub.SIZE_Y - 0.5D
                && pos.z > StarterSub.ORIGIN_Z && pos.z < StarterSub.ORIGIN_Z + StarterSub.SIZE_Z - 1;
    }

    /**
     * 方块船（还没收成 VS2 刚体的初始潜艇等）的干燥区。
     *
     * <p>服务端没有客户端水面包，只能找附近已加载的船核心；玩家出生在初始潜艇里时，
     * 这条判断保证不会一进游戏就开始掉氧。物理船的干燥区由 {@link #dryZone} 负责，
     * 这里在物理船激活时直接跳过。</p>
     */
    public static boolean isInsideBlockShipDryZone(Level level, Vec3 pos) {
        if (!isInOcean(level, pos) || isInOutpostDryZone(pos) || VsState.active()) {
            return false;
        }
        boolean starterFallback = isInsideStarterSubDryZone(pos);
        ShipCoreBlockEntity core = ShipCores.nearestCore(level, BlockPos.containing(pos), 32.0D);
        ShipState state = core == null ? null : core.state();
        if (state == null) {
            // 船状态还没扫出来：初始潜艇内腔按干燥兜底，别让玩家一出生就掉氧。
            return starterFallback;
        }
        // 按游戏刻缓存：同一 tick 里每个玩家都会问一次，不能每次都重建水面包
        // （build 里的矩形分解和连海空腔松弛远比这次包围盒判定贵）。
        float[] boxes = state.waterBoxesFor(level.getGameTime()).boxes();
        if (boxes.length < 7) {
            return starterFallback;
        }
        for (int i = 0; i + 6 < boxes.length; i += 7) {
            float minX = boxes[i];
            float minY = boxes[i + 1];
            float minZ = boxes[i + 2];
            float maxX = boxes[i + 3] + 1.0F;
            float maxY = boxes[i + 4] + 1.0F;
            float maxZ = boxes[i + 5] + 1.0F;
            float surface = boxes[i + 6];
            // 干燥舱室允许眼睛在跳跃顶点短暂高过顶格；进水后仍以水面为准。
            boolean dryBox = surface <= minY + 0.01F;
            float top = maxY + (dryBox ? 1.0F : 0.0F);
            if (pos.x >= minX && pos.x <= maxX
                    && pos.y >= minY && pos.y <= top
                    && pos.z >= minZ && pos.z <= maxZ
                    && (dryBox || pos.y > surface + 0.01F)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这个位置是否应该按“浸没”处理：在水柱内、不在哨站、也不在未进水的船舱里。
     */
    public static boolean isSubmerged(Level level, Vec3 pos) {
        if (!isInOcean(level, pos)) {
            return false;
        }
        // 按 side 选干燥区：单人存档里渲染线程和集成服务端在同一进程，
        // 不能用同一份注册状态。
        DryZone zone = level.isClientSide() ? clientDryZone : serverDryZone;
        // 船舱内部只听船的：进了水的舱哪怕停在哨站里也是浸没。
        if (zone != null && zone.isInsideCompartment(level, pos)) {
            return !zone.isDry(level, pos);
        }
        if (isInOutpostDryZone(pos)) {
            return false;
        }
        return zone == null || !zone.isDry(level, pos);
    }
}
