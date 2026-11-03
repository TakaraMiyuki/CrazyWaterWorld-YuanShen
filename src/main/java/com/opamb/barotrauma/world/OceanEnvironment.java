package com.opamb.barotrauma.world;

import com.opamb.barotrauma.blockentity.ShipCoreBlockEntity;
import com.opamb.barotrauma.network.ShipWaterStatePacket;
import com.opamb.barotrauma.ship.ShipCores;
import com.opamb.barotrauma.ship.ShipState;
import com.opamb.barotrauma.ship.ShipWaterStateBuilder;
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
    }

    private static volatile DryZone dryZone;

    private OceanEnvironment() {
    }

    /** 由物理船实现注册；没有物理船时允许为 null。 */
    public static void setDryZone(DryZone zone) {
        dryZone = zone;
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
        return Math.abs(pos.x) <= OutpostBuilder.HALF_X
                && Math.abs(pos.z) <= OutpostBuilder.HALF_Z
                && pos.y >= OutpostBuilder.MIN_Y
                && pos.y <= OutpostBuilder.MAX_Y;
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
        ShipCoreBlockEntity core = ShipCores.nearestCore(level, BlockPos.containing(pos), 32.0D);
        if (core == null) {
            return false;
        }
        ShipState state = core.state();
        if (state == null) {
            return false;
        }
        ShipWaterStatePacket packet = ShipWaterStateBuilder.build(state, -1L, false, 0.0F, 0.0F, 0.0F);
        float[] boxes = packet.boxes();
        for (int i = 0; i + 6 < boxes.length; i += 7) {
            float minX = boxes[i];
            float minY = boxes[i + 1];
            float minZ = boxes[i + 2];
            float maxX = boxes[i + 3] + 1.0F;
            float maxY = boxes[i + 4] + 1.0F;
            float maxZ = boxes[i + 5] + 1.0F;
            float surface = boxes[i + 6];
            if (pos.x >= minX && pos.x <= maxX
                    && pos.y >= minY && pos.y <= maxY
                    && pos.z >= minZ && pos.z <= maxZ
                    && pos.y > surface + 0.01F) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这个位置是否应该按“浸没”处理：在水柱内、不在哨站、也不在未进水的船舱里。
     */
    public static boolean isSubmerged(Level level, Vec3 pos) {
        if (!isInOcean(level, pos) || isInOutpostDryZone(pos)) {
            return false;
        }
        DryZone zone = dryZone;
        return zone == null || !zone.isDry(level, pos);
    }
}
