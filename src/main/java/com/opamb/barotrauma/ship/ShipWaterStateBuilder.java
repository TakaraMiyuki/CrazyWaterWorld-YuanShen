package com.opamb.barotrauma.ship;

import com.opamb.barotrauma.network.ShipWaterStatePacket;
import net.minecraft.core.BlockPos;

import java.util.List;

/** 把船状态翻译成"客户端水面渲染"用的数据包（物理船/方块船共用）。 */
public final class ShipWaterStateBuilder {

    private ShipWaterStateBuilder() {
    }

    public static ShipWaterStatePacket build(ShipState state, long shipId, boolean physical,
                                             float anchorX, float anchorY, float anchorZ) {
        List<ShipGeometry.Compartment> compartments = state.compartments();
        List<ShipGeometry.Compartment> pockets = state.geometry().openPockets();
        float[] boxes = new float[(compartments.size() + pockets.size()) * 7];
        int offset = 0;
        for (int i = 0; i < compartments.size(); i++) {
            offset = append(boxes, offset, compartments.get(i), state.simulation().waterUnits(i));
        }
        for (int i = 0; i < pockets.size(); i++) {
            offset = append(boxes, offset, pockets.get(i), state.simulation().pocketWater(i));
        }
        return new ShipWaterStatePacket(shipId, physical, anchorX, anchorY, anchorZ, boxes);
    }

    /**
     * 把一个舱室/空腔写成 7 个 float。
     *
     * <p>水面高度必须按<b>实际逐层格数</b>算，不能用包围盒底面积：
     * 舱室很少是完整长方体（破口空腔、L 形舱、被机器占掉的格子都很常见）。
     * 用 bbox 面积会把水位算高/算低，和 {@link ShipWater} 的"从舱底逐格铺水"对不上。</p>
     *
     * <p>协议仍然只发 bbox + surface（没有逐格几何），所以水平范围仍是近似的；
     * 这一层限制记录在 PLAN/HANDOFF 里，不把 bbox 当成最终真实水面。</p>
     */
    private static int append(float[] boxes, int offset, ShipGeometry.Compartment compartment, double units) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        // 每个 Y 层有多少格；按 Y 升序累计，和 ShipWater 的填水顺序一致。
        java.util.TreeMap<Integer, Integer> cellsPerLayer = new java.util.TreeMap<>();
        for (BlockPos cell : compartment.cells()) {
            minX = Math.min(minX, cell.getX());
            minY = Math.min(minY, cell.getY());
            minZ = Math.min(minZ, cell.getZ());
            maxX = Math.max(maxX, cell.getX());
            maxY = Math.max(maxY, cell.getY());
            maxZ = Math.max(maxZ, cell.getZ());
            cellsPerLayer.merge(cell.getY(), 1, Integer::sum);
        }
        int volume = compartment.volume();
        double filled = Math.max(0.0D, units / ShipSimulation.UNITS_PER_BLOCK);
        float surface;
        if (volume <= 0 || filled <= 0.0D) {
            surface = minY;
        } else if (filled >= volume) {
            surface = maxY + 1.0F;
        } else {
            double remaining = filled;
            float candidate = maxY + 1.0F;
            for (java.util.Map.Entry<Integer, Integer> layer : cellsPerLayer.entrySet()) {
                int count = layer.getValue();
                if (count <= 0) {
                    continue;
                }
                if (remaining >= count) {
                    remaining -= count;
                    candidate = layer.getKey() + 1.0F;
                } else {
                    candidate = layer.getKey() + (float) (remaining / count);
                    break;
                }
            }
            surface = candidate;
        }
        surface = Math.max((float) minY, Math.min((float) maxY + 1.0F, surface));
        boxes[offset++] = minX;
        boxes[offset++] = minY;
        boxes[offset++] = minZ;
        boxes[offset++] = maxX;
        boxes[offset++] = maxY;
        boxes[offset++] = maxZ;
        boxes[offset++] = surface;
        return offset;
    }
}
