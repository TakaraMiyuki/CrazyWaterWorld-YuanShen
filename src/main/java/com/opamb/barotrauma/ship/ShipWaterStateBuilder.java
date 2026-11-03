package com.opamb.barotrauma.ship;

import com.opamb.barotrauma.network.ShipWaterStatePacket;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 把船状态翻译成"客户端水面渲染"用的数据包（物理船/方块船共用）。
 *
 * <p>每个舱室/连海空腔不再只发一个包围盒，而是拆成若干个**矩形棱柱**
 * （每个棱柱 7 个 float：minX/minY/minZ/maxX/maxY/maxZ + 水面高度）。
 * 包围盒会把非矩形舱室和它旁边的密闭小间一起盖住——用户实测"隔离的舱也在涨水"
 * 就是这个问题（连海空腔的 bbox 包住了旁边的密闭小间）。棱柱只覆盖真实格子，
 * 水面就不会画到别的舱里。</p>
 */
public final class ShipWaterStateBuilder {

    /**
     * 单个舱室最多拆成多少个棱柱。
     *
     * <p>正常房间逐层贪心切矩形只有几个；只有极端锯齿形状才会爆。超过上限时退回
     * 整个 bbox（旧行为），宁可视觉上多盖一点，也不能把网络包撑爆。</p>
     */
    private static final int MAX_BOXES_PER_COMPARTMENT = 512;

    private ShipWaterStateBuilder() {
    }

    public static ShipWaterStatePacket build(ShipState state, long shipId, boolean physical,
                                             float anchorX, float anchorY, float anchorZ) {
        List<ShipGeometry.Compartment> compartments = state.compartments();
        List<ShipGeometry.Compartment> pockets = state.geometry().openPockets();
        List<float[]> boxes = new ArrayList<>();
        // 每个连海空腔第一个"有水"棱柱在 boxes 里的下标：破口用它取水面（同舱所有棱柱水面相同）。
        int[] pocketBoxIndex = new int[pockets.size()];
        // 破口是水的入口：连海空腔的水面/含水格子按它算，隔墙顶部的孔不会提前串水。
        List<List<BlockPos>> pocketSources = new ArrayList<>(pockets.size());
        for (int i = 0; i < pockets.size(); i++) {
            pocketSources.add(new ArrayList<>());
        }
        for (ShipState.Breach breach : state.breaches()) {
            int pocket = pocketIndex(pockets, breach.pos());
            if (pocket >= 0) {
                pocketSources.get(pocket).add(breach.pos());
            }
        }
        for (int i = 0; i < compartments.size(); i++) {
            appendCompartment(boxes, compartments.get(i), state.simulation().waterUnits(i), null);
        }
        for (int i = 0; i < pockets.size(); i++) {
            pocketBoxIndex[i] = appendCompartment(boxes, pockets.get(i),
                    state.simulation().pocketWater(i), pocketSources.get(i));
        }

        // 每个破口 8 个 float：格中心 x/y/z + boxes 里的舱室下标 + 朝舱内的水平方向
        // + 所在空腔的 minY/maxY（客户端据此判断"顶部破口"和"是否灌满"）。
        float[] breaches = new float[state.breaches().size() * 8];
        int breachOffset = 0;
        for (ShipState.Breach breach : state.breaches()) {
            int pocket = pocketIndex(pockets, breach.pos());
            if (pocket < 0) {
                continue;
            }
            float[] range = yRange(pockets.get(pocket));
            breaches[breachOffset++] = breach.pos().getX() + 0.5F;
            breaches[breachOffset++] = breach.pos().getY() + 0.5F;
            breaches[breachOffset++] = breach.pos().getZ() + 0.5F;
            breaches[breachOffset++] = pocketBoxIndex[pocket];
            breaches[breachOffset++] = breach.dirX();
            breaches[breachOffset++] = breach.dirZ();
            breaches[breachOffset++] = range[0];
            breaches[breachOffset++] = range[1];
        }
        if (breachOffset != breaches.length) {
            breaches = java.util.Arrays.copyOf(breaches, breachOffset);
        }

        float[] flat = new float[boxes.size() * 7];
        int offset = 0;
        for (float[] box : boxes) {
            System.arraycopy(box, 0, flat, offset, 7);
            offset += 7;
        }
        return new ShipWaterStatePacket(shipId, physical, anchorX, anchorY, anchorZ, flat, breaches);
    }

    private static int pocketIndex(List<ShipGeometry.Compartment> pockets, BlockPos pos) {
        for (int i = 0; i < pockets.size(); i++) {
            if (pockets.get(i).cells().contains(pos)) {
                return i;
            }
        }
        return -1;
    }

    /** 舱室的 minY/maxY。 */
    private static float[] yRange(ShipGeometry.Compartment compartment) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (BlockPos cell : compartment.cells()) {
            minY = Math.min(minY, cell.getY());
            maxY = Math.max(maxY, cell.getY());
        }
        if (minY == Integer.MAX_VALUE) {
            return new float[]{0.0F, 0.0F};
        }
        return new float[]{minY, maxY};
    }

    /**
     * 把一个舱室/空腔写进 {@code boxes}，返回第一个"有水"块的下标。
     *
     * <p>连海空腔按 1×1 竖直列发（每列再按连续 Y 拆段）：每列有自己的水位，
     * 隔墙上的孔只有在某一列的水面漫到孔底后才把水传给下一列，多个隔断会依次传递。
     * 密闭舱室仍然用整块水位。</p>
     *
     * <p>块列表只由几何决定、不随水位变化：含水的列用当前水位，没水的列用自己的
     * 舱底（渲染端跳过）。这样客户端的水面平滑数组长度稳定，水位可以逐帧插值。</p>
     */
    private static int appendCompartment(List<float[]> boxes, ShipGeometry.Compartment compartment,
                                         double units, List<BlockPos> sources) {
        int start = boxes.size();
        int[] bounds = boundsOf(compartment);
        if (bounds == null) {
            return start;
        }
        float minY = bounds[1];
        // 0 水量：水面 = minY，渲染端会跳过；发一个 bbox 给干燥区判定用。
        if (units <= 0.0D) {
            boxes.add(new float[]{bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5], minY});
            return start;
        }
        if (sources != null && !sources.isEmpty()) {
            ShipPocketWater.Shape shape = ShipPocketWater.compute(compartment.cells(), sources, units);
            if (!shape.columns().isEmpty()) {
                return appendByColumn(boxes, compartment.cells(), shape);
            }
        }
        // 密闭舱室 / 没有破口记录的空腔：旧的"从舱底整层铺水"模型。
        float surface = surfaceOf(compartment, units);
        if (surface <= minY + 0.01F) {
            boxes.add(new float[]{bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5], minY});
            return start;
        }
        List<int[]> prisms = decompose(compartment.cells());
        if (prisms.isEmpty()) {
            return start;
        }
        if (prisms.size() > MAX_BOXES_PER_COMPARTMENT) {
            boxes.add(new float[]{bounds[0], bounds[1], bounds[2],
                    bounds[3], bounds[4], bounds[5], surface});
            return start;
        }
        for (int[] prism : prisms) {
            boxes.add(new float[]{prism[0], prism[1], prism[2], prism[3], prism[4], prism[5], surface});
        }
        return start;
    }

    /**
     * 连海空腔按列发：每列一个盒子（Y 有断档时拆成多段），水面 = 该列水位
     * 但不超过这一段的顶格。
     */
    private static int appendByColumn(List<float[]> boxes, Set<BlockPos> cells,
                                      ShipPocketWater.Shape shape) {
        TreeMap<Long, List<Integer>> columns = new TreeMap<>();
        for (BlockPos cell : cells) {
            columns.computeIfAbsent(packXZ(cell.getX(), cell.getZ()), key -> new ArrayList<>())
                    .add(cell.getY());
        }
        int firstWet = -1;
        for (Map.Entry<Long, List<Integer>> entry : columns.entrySet()) {
            int x = unpackX(entry.getKey());
            int z = unpackZ(entry.getKey());
            List<Integer> ys = entry.getValue();
            ys.sort(Integer::compare);
            float level = shape.levelAt(x, z);
            int i = 0;
            while (i < ys.size()) {
                int runStart = ys.get(i);
                int runEnd = runStart;
                i++;
                while (i < ys.size() && ys.get(i) == runEnd + 1) {
                    runEnd = ys.get(i);
                    i++;
                }
                float waterLevel = runStart;
                if (!Float.isNaN(level) && level > runStart + 0.001F) {
                    waterLevel = quantize(Math.min(level, runEnd + 1.0F));
                }
                int boxIndex = boxes.size();
                boxes.add(new float[]{x, runStart, z, x, runEnd, z, waterLevel});
                if (waterLevel > runStart + 0.01F && firstWet < 0) {
                    firstWet = boxIndex;
                }
            }
        }
        return firstWet < 0 ? 0 : firstWet;
    }

    /** 水面量化到 1/64 格：相邻列同水位时浮点完全相等，客户端才能把内部面剔掉。 */
    private static float quantize(float surface) {
        return Math.round(surface * 64.0F) / 64.0F;
    }

    private static int[] boundsOf(ShipGeometry.Compartment compartment) {
        return boundsOf(compartment.cells());
    }

    private static int[] boundsOf(Set<BlockPos> cells) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos cell : cells) {
            minX = Math.min(minX, cell.getX());
            minY = Math.min(minY, cell.getY());
            minZ = Math.min(minZ, cell.getZ());
            maxX = Math.max(maxX, cell.getX());
            maxY = Math.max(maxY, cell.getY());
            maxZ = Math.max(maxZ, cell.getZ());
        }
        if (minX == Integer.MAX_VALUE) {
            return null;
        }
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    /**
     * 把一组格子拆成尽量少的矩形棱柱：逐 Y 层贪心切二维矩形，再把上下层完全相同的
     * 矩形合并成一根柱子。
     *
     * @return 每个元素 {minX, minY, minZ, maxX, maxY, maxZ}
     */
    private static List<int[]> decompose(Set<BlockPos> cells) {
        TreeMap<Integer, Set<Long>> layers = new TreeMap<>();
        for (BlockPos cell : cells) {
            layers.computeIfAbsent(cell.getY(), y -> new HashSet<>())
                    .add(packXZ(cell.getX(), cell.getZ()));
        }
        List<int[]> prisms = new ArrayList<>();
        // 上一层的矩形 footprint → 对应的柱子；下一层同样的 footprint 就延长它。
        Map<RectKey, int[]> open = new HashMap<>();
        for (Map.Entry<Integer, Set<Long>> layer : layers.entrySet()) {
            int y = layer.getKey();
            Map<RectKey, int[]> next = new HashMap<>();
            for (int[] rect : decomposeLayer(layer.getValue())) {
                RectKey key = new RectKey(rect[0], rect[1], rect[2], rect[3]);
                int[] prism = open.get(key);
                if (prism != null) {
                    prism[4] = y;
                    next.put(key, prism);
                } else {
                    int[] created = {rect[0], y, rect[1], rect[2], y, rect[3]};
                    prisms.add(created);
                    next.put(key, created);
                }
            }
            open = next;
        }
        return prisms;
    }

    /** 单层贪心：按 x/z 排序，先尽量向 +X 延伸，再整行向 +Z 延伸。 */
    private static List<int[]> decomposeLayer(Set<Long> layer) {
        List<long[]> cells = new ArrayList<>(layer.size());
        for (long packed : layer) {
            cells.add(new long[]{unpackX(packed), unpackZ(packed)});
        }
        cells.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        Set<Long> remaining = new HashSet<>(layer);
        List<int[]> rects = new ArrayList<>();
        for (long[] cell : cells) {
            long key = packXZ((int) cell[0], (int) cell[1]);
            if (!remaining.remove(key)) {
                continue;
            }
            int minX = (int) cell[0];
            int minZ = (int) cell[1];
            int maxX = minX;
            int maxZ = minZ;
            while (remaining.contains(packXZ(maxX + 1, minZ))) {
                maxX++;
                remaining.remove(packXZ(maxX, minZ));
            }
            boolean grow = true;
            while (grow) {
                for (int x = minX; x <= maxX; x++) {
                    if (!remaining.contains(packXZ(x, maxZ + 1))) {
                        grow = false;
                        break;
                    }
                }
                if (grow) {
                    maxZ++;
                    for (int x = minX; x <= maxX; x++) {
                        remaining.remove(packXZ(x, maxZ));
                    }
                }
            }
            rects.add(new int[]{minX, minZ, maxX, maxZ});
        }
        return rects;
    }

    private static long packXZ(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackZ(long packed) {
        return (int) packed;
    }

    /** 矩形 footprint 作为合并键：不同形状不能混在一起。 */
    private record RectKey(int minX, int minZ, int maxX, int maxZ) {
    }

    /**
     * 一个舱室的水面高度。
     *
     * <p>必须按<b>实际逐层格数</b>算，不能用包围盒底面积：
     * 舱室很少是完整长方体（破口空腔、L 形舱、被机器占掉的格子都很常见）。
     * 用 bbox 面积会把水位算高/算低，和 {@link ShipWater} 的"从舱底逐格铺水"对不上。</p>
     */
    static float surfaceOf(ShipGeometry.Compartment compartment, double units) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        // 每个 Y 层有多少格；按 Y 升序累计，和 ShipWater 的填水顺序一致。
        TreeMap<Integer, Integer> cellsPerLayer = new TreeMap<>();
        for (BlockPos cell : compartment.cells()) {
            minY = Math.min(minY, cell.getY());
            maxY = Math.max(maxY, cell.getY());
            cellsPerLayer.merge(cell.getY(), 1, Integer::sum);
        }
        if (minY == Integer.MAX_VALUE) {
            return 0.0F;
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
            for (Map.Entry<Integer, Integer> layer : cellsPerLayer.entrySet()) {
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
        return Math.max((float) minY, Math.min((float) maxY + 1.0F, surface));
    }
}
