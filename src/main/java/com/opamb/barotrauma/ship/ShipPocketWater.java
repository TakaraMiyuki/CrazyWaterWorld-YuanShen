package com.opamb.barotrauma.ship;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;

/**
 * 破口空腔里的水怎么分布：按竖直列维护水位，列之间通过共享格子流动。
 *
 * <p>旧模型是“整个空腔一个水位”，等于默认空腔里所有空气都是连通水道：隔墙顶上
 * 留孔时，破口那侧刚开始进水，另一侧就同步涨水；有多个隔断时水越过第一个就
 * 无视后面的，全部一起涨。用户把这叫“假物理”。</p>
 *
 * <p>这里把空腔拆成 1×1 的竖直列，每列一个水位。相邻两列只有共享空气格子才能
 * 过水，最低的共享格子就是“溢流口”高度：高的一侧可以往低的一侧流，但自己的
 * 水面不能低于溢流口。于是：破口所在的列先涨到隔断孔底，再从孔流到下一列，
 * 每一列按自己的水位慢慢上涨；多个隔断会依次传递，而不是一起涨。</p>
 */
public final class ShipPocketWater {

    /** 最高水位（给破口粒子判断气/水侧用）+ 每列的水位（key = (x,z) 打包）。 */
    public record Shape(float surface, Map<Long, Float> columns) {
        public float levelAt(int x, int z) {
            Float value = columns.get(pack(x, z));
            return value == null ? Float.NaN : value;
        }
    }

    /** 松弛迭代次数：10 Hz 发包，不需要完全收敛，每 tick 继续逼近即可。 */
    private static final int RELAX_ITERATIONS = 64;

    private ShipPocketWater() {
    }

    public static Shape compute(Set<BlockPos> cells, Collection<BlockPos> sources, double units) {
        if (cells.isEmpty()) {
            return new Shape(0.0F, Map.of());
        }
        int minY = Integer.MAX_VALUE;
        for (BlockPos cell : cells) {
            minY = Math.min(minY, cell.getY());
        }
        double volume = Math.max(0.0D, units / ShipSimulation.UNITS_PER_BLOCK);
        if (volume <= 0.0D) {
            return new Shape(minY, Map.of());
        }

        // ---- 按 (x,z) 分列，每列 Y 升序 ----
        Map<Long, List<Integer>> columnCells = new HashMap<>();
        for (BlockPos cell : cells) {
            columnCells.computeIfAbsent(pack(cell.getX(), cell.getZ()), key -> new ArrayList<>())
                    .add(cell.getY());
        }
        List<Long> columnKeys = new ArrayList<>(columnCells.keySet());
        columnKeys.sort(Long::compare);
        int columnCount = columnKeys.size();
        Map<Long, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < columnCount; i++) {
            indexOf.put(columnKeys.get(i), i);
        }
        int[][] ys = new int[columnCount][];
        for (int i = 0; i < columnCount; i++) {
            List<Integer> values = columnCells.get(columnKeys.get(i));
            values.sort(Integer::compare);
            ys[i] = new int[values.size()];
            for (int j = 0; j < values.size(); j++) {
                ys[i][j] = values.get(j);
            }
        }

        // ---- 初始分布：按“从破口往下落、最低处先填”的顺序把水量铺成格子 ----
        float[] level = new float[columnCount];
        for (int i = 0; i < columnCount; i++) {
            level[i] = ys[i][0];
        }
        BlockPos source = null;
        for (BlockPos candidate : sources) {
            if (cells.contains(candidate)) {
                source = candidate;
                break;
            }
        }
        if (source == null) {
            return new Shape(minY, Map.of());
        }
        spreadFromSource(cells, source, volume, columnCells, indexOf, level);

        // ---- 相邻列之间松弛；最低共享格子 = 溢流口 ----
        List<Edge> edges = buildEdges(cells, indexOf, ys);
        for (int iteration = 0; iteration < RELAX_ITERATIONS; iteration++) {
            boolean changed = false;
            for (Edge edge : edges) {
                if (relax(edge, ys, level)) {
                    changed = true;
                }
            }
            // 两两松弛沿长链收敛太慢；这里把“两侧都淹没的连接”并成一个连通器，
            // 整组直接取同一水位，几轮就能收敛。
            if (!changed) {
                break;
            }
        }

        Map<Long, Float> result = new HashMap<>(columnCount * 2);
        float surface = minY;
        for (int i = 0; i < columnCount; i++) {
            // 量化到 1/64 格：同水位的列浮点完全相等，客户端才能把内部面剔掉。
            float value = Math.round(level[i] * 64.0F) / 64.0F;
            result.put(columnKeys.get(i), value);
            if (value > ys[i][0] + 0.01F) {
                surface = Math.max(surface, value);
            }
        }
        return new Shape(surface, result);
    }

    // ------------------------------------------------------------------ 初始分布

    /**
     * 用最小堆按高度从破口往下铺：先填最低的格子，破口在水面以上时也会顺着
     * 竖直柱落到舱底。这里不处理水平平整，交给后面的松弛。
     */
    private static void spreadFromSource(Set<BlockPos> cells, BlockPos source, double volume,
                                         Map<Long, List<Integer>> columnCells,
                                         Map<Long, Integer> indexOf, float[] level) {
        int needed = (int) Math.ceil(volume);
        if (needed <= 0) {
            return;
        }
        PriorityQueue<BlockPos> queue = new PriorityQueue<>(
                (a, b) -> a.getY() != b.getY() ? Integer.compare(a.getY(), b.getY())
                        : a.getX() != b.getX() ? Integer.compare(a.getX(), b.getX())
                        : Integer.compare(a.getZ(), b.getZ()));
        Set<BlockPos> seen = new java.util.HashSet<>();
        queue.add(source.immutable());
        seen.add(source.immutable());
        List<BlockPos> order = new ArrayList<>(needed);
        int limit = Math.min(needed, cells.size());
        while (!queue.isEmpty() && order.size() < limit) {
            BlockPos pos = queue.poll();
            order.add(pos);
            for (BlockPos next : new BlockPos[]{pos.above(), pos.below(), pos.north(),
                    pos.south(), pos.east(), pos.west()}) {
                if (cells.contains(next) && seen.add(next)) {
                    queue.add(next.immutable());
                }
            }
        }
        int full = (int) Math.floor(volume);
        float fraction = (float) (volume - full);
        for (int i = 0; i < Math.min(full, order.size()); i++) {
            BlockPos cell = order.get(i);
            int index = indexOf.get(pack(cell.getX(), cell.getZ()));
            level[index] = Math.max(level[index], cell.getY() + 1.0F);
        }
        if (fraction > 0.0F && full < order.size()) {
            BlockPos cell = order.get(full);
            int index = indexOf.get(pack(cell.getX(), cell.getZ()));
            level[index] = Math.max(level[index], cell.getY() + fraction);
        }
    }

    // ------------------------------------------------------------------ 松弛

    private record Edge(int a, int b, int sill) {
    }

    private static List<Edge> buildEdges(Set<BlockPos> cells,
                                         Map<Long, Integer> indexOf, int[][] ys) {
        Map<Long, Integer> sills = new HashMap<>();
        for (BlockPos cell : cells) {
            for (int[] step : new int[][]{{1, 0}, {0, 1}}) {
                BlockPos other = new BlockPos(cell.getX() + step[0], cell.getY(), cell.getZ() + step[1]);
                if (!cells.contains(other)) {
                    continue;
                }
                int a = indexOf.get(pack(cell.getX(), cell.getZ()));
                int b = indexOf.get(pack(other.getX(), other.getZ()));
                if (a == b) {
                    continue;
                }
                long key = a < b ? ((long) a << 32) | (b & 0xFFFFFFFFL)
                        : ((long) b << 32) | (a & 0xFFFFFFFFL);
                sills.merge(key, cell.getY(), Math::min);
            }
        }
        List<Edge> edges = new ArrayList<>(sills.size());
        for (Map.Entry<Long, Integer> entry : sills.entrySet()) {
            long key = entry.getKey();
            int a = (int) (key >> 32);
            int b = (int) key;
            edges.add(new Edge(a, b, entry.getValue()));
        }
        return edges;
    }

    /** 高的一侧往低的一侧流；高侧水位不会降到溢流口以下。返回是否发生了变化。 */
    private static boolean relax(Edge edge, int[][] ys, float[] level) {
        int hi = edge.a;
        int lo = edge.b;
        if (level[hi] < level[lo]) {
            int swap = hi;
            hi = lo;
            lo = swap;
        }
        float highLevel = level[hi];
        float lowLevel = level[lo];
        if (highLevel - lowLevel < 1.0E-4F) {
            return false;
        }
        if (highLevel <= edge.sill + 1.0E-4F) {
            // 高侧自己还没漫过溢流口，流不过去
            return false;
        }
        double highVolume = volumeAt(ys[hi], highLevel);
        double lowVolume = volumeAt(ys[lo], lowLevel);
        double total = highVolume + lowVolume;
        // 先按“两侧连通”找共同水位；够不到溢流口时，高侧放到溢流口，剩下的全给低侧。
        float equal = equalLevel(ys[hi], ys[lo], total);
        if (equal >= edge.sill - 1.0E-4F) {
            if (Math.abs(equal - highLevel) < 1.0E-4F && Math.abs(equal - lowLevel) < 1.0E-4F) {
                return false;
            }
            level[hi] = equal;
            level[lo] = equal;
            return true;
        }
        double room = ys[lo].length - lowVolume;
        // 高侧高于溢流口的那部分水全部流过去；高侧水面正好停在溢流口。
        double aboveSill = highVolume - volumeAt(ys[hi], edge.sill);
        double amount = Math.min(aboveSill, room);
        if (amount <= 1.0E-4D) {
            return false;
        }
        level[hi] = levelAtVolume(ys[hi], highVolume - amount);
        level[lo] = levelAtVolume(ys[lo], lowVolume + amount);
        return true;
    }

    /** 两列连通后的共同水位（按两列各自的实际格子算）。 */
    private static float equalLevel(int[] a, int[] b, double total) {
        float lo = Math.min(a[0], b[0]);
        float hi = Math.max(a[a.length - 1], b[b.length - 1]) + 1.0F;
        for (int i = 0; i < 24; i++) {
            float mid = (lo + hi) * 0.5F;
            if (volumeAt(a, mid) + volumeAt(b, mid) < total) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) * 0.5F;
    }

    private static double volumeAt(int[] ys, float level) {
        double volume = 0.0D;
        for (int y : ys) {
            if (level <= y) {
                break;
            }
            volume += Math.min(1.0D, level - y);
        }
        return volume;
    }

    private static float levelAtVolume(int[] ys, double volume) {
        if (volume <= 0.0D) {
            return ys[0];
        }
        double remaining = volume;
        for (int y : ys) {
            if (remaining >= 1.0D) {
                remaining -= 1.0D;
                continue;
            }
            return y + (float) remaining;
        }
        return ys[ys.length - 1] + 1.0F;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }
}
