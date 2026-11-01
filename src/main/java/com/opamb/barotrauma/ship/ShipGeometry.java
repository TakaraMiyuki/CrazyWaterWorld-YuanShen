package com.opamb.barotrauma.ship;

import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 船体几何：先认出"船"，再把船内部的空腔划分成舱室。
 *
 * <p>两步：</p>
 * <ol>
 *   <li><b>找船</b>：在核心周围半径内收集所有船体方块（装甲板/舷窗/舱门/机器），
 *       做连通分量，取最大的那块作为这艘船，得到它的包围盒；</li>
 *   <li><b>分舱</b>：只在包围盒内对非固体格子做 BFS。碰到包围盒边界 = 连到外海（破洞或没封口），
 *       不算舱室；否则就是一个封闭舱室（门关着自然就被隔开）。</li>
 * </ol>
 *
 * <p>纯逻辑，不依赖 Level，方便以后单测。</p>
 */
public final class ShipGeometry {

    /** 世界探针：由调用方提供方块/水判定。 */
    public interface Probe {
        boolean isSolid(BlockPos pos);

        boolean isWater(BlockPos pos);
    }

    /** 一个封闭舱室。 */
    public record Compartment(Set<BlockPos> cells, int waterBlocks) {
        public int volume() {
            return cells.size();
        }

        public double waterRatio() {
            return volume() == 0 ? 0.0D : waterBlocks / (double) volume();
        }
    }

    /** 包围盒。 */
    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }

        public int sizeX() {
            return maxX - minX + 1;
        }

        public int sizeY() {
            return maxY - minY + 1;
        }

        public int sizeZ() {
            return maxZ - minZ + 1;
        }

        public Bounds shifted(int dx, int dy, int dz) {
            return new Bounds(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
        }
    }

    /** 扫描结果。{@code openPockets} 是连到外海的空腔。 */
    public record Result(List<Compartment> compartments, List<Compartment> openPockets,
                         int hullBlocks, int scannedCells, Bounds bounds, Set<BlockPos> hull) {
        public int totalVolume() {
            return compartments.stream().mapToInt(Compartment::volume).sum();
        }

        public boolean sealed() {
            return openPockets.isEmpty();
        }

        /**
         * 整艘船平移后的几何。
         *
         * <p>船是刚体：挪一格之后舱室划分、容积、破口分布全都不变，只是坐标变了。
         * 所以推进时不需要重新扫描（一次重扫要 13 万次方块查询），直接平移即可，
         * 顺带也让温度 / 水位这些模拟状态不会因为"换了艘新船"被重置。</p>
         */
        public Result shifted(int dx, int dy, int dz) {
            Set<BlockPos> movedHull = new HashSet<>(Math.max(16, hull.size() * 2));
            for (BlockPos pos : hull) {
                movedHull.add(pos.offset(dx, dy, dz));
            }
            return new Result(shift(compartments, dx, dy, dz), shift(openPockets, dx, dy, dz),
                    hullBlocks, scannedCells, bounds.shifted(dx, dy, dz), movedHull);
        }

        private static List<Compartment> shift(List<Compartment> source, int dx, int dy, int dz) {
            List<Compartment> out = new ArrayList<>(source.size());
            for (Compartment compartment : source) {
                Set<BlockPos> cells = new HashSet<>(Math.max(16, compartment.cells().size() * 2));
                for (BlockPos pos : compartment.cells()) {
                    cells.add(pos.offset(dx, dy, dz));
                }
                out.add(new Compartment(cells, compartment.waterBlocks()));
            }
            return out;
        }
    }

    /** 单次分舱 BFS 的格子上限。 */
    private static final int MAX_CELLS_PER_FILL = 30_000;

    private ShipGeometry() {
    }

    /**
     * @param core        船核心位置
     * @param radius      收集船体方块的半径
     * @param isShipBlock 判定某格是不是船体方块
     * @param probe       实心/水判定
     */
    public static Result scanShip(BlockPos core, int radius, Predicate<BlockPos> isShipBlock, Probe probe) {
        Set<BlockPos> hull = collectHull(core, radius, isShipBlock);
        if (hull.isEmpty()) {
            return new Result(List.of(), List.of(), 0, 0, new Bounds(core.getX(), core.getY(), core.getZ(),
                    core.getX(), core.getY(), core.getZ()), Set.of());
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : hull) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }

        // ---- 3) 包围盒内分舱 ----
        List<Compartment> compartments = new ArrayList<>();
        List<Compartment> openPockets = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int scanned = 0;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    BlockPos pos = cursor.immutable();
                    if (visited.contains(pos) || probe.isSolid(pos)) {
                        continue;
                    }
                    Flood flood = flood(pos, minX, minY, minZ, maxX, maxY, maxZ, probe, visited);
                    if (flood == null) {
                        continue;
                    }
                    scanned += flood.cells.size();
                    Compartment compartment = new Compartment(flood.cells, flood.waterBlocks);
                    if (flood.touchesBoundary) {
                        openPockets.add(compartment);
                    } else {
                        compartments.add(compartment);
                    }
                }
            }
        }
        return new Result(compartments, openPockets, hull.size(), scanned,
                new Bounds(minX, minY, minZ, maxX, maxY, maxZ), hull);
    }

    /** 收集船体方块：半径内所有船体方块里，**离核心最近**的那个连通块（不是最大的）。 */
    public static Set<BlockPos> collectHull(BlockPos core, int radius, Predicate<BlockPos> isShipBlock) {
        Set<BlockPos> shipBlocks = new HashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = core.getX() - radius; x <= core.getX() + radius; x++) {
            for (int y = core.getY() - radius; y <= core.getY() + radius; y++) {
                for (int z = core.getZ() - radius; z <= core.getZ() + radius; z++) {
                    cursor.set(x, y, z);
                    BlockPos pos = cursor.immutable();
                    if (isShipBlock.test(pos)) {
                        shipBlocks.add(pos);
                    }
                }
            }
        }
        if (shipBlocks.isEmpty()) {
            return Set.of();
        }
        Set<BlockPos> best = Set.of();
        double bestDistance = Double.MAX_VALUE;
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos start : shipBlocks) {
            if (!seen.add(start)) {
                continue;
            }
            Set<BlockPos> component = new HashSet<>();
            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            double nearest = Double.MAX_VALUE;
            while (!queue.isEmpty()) {
                BlockPos pos = queue.poll();
                component.add(pos);
                nearest = Math.min(nearest, pos.distSqr(core));
                for (BlockPos next : neighbours(pos)) {
                    if (shipBlocks.contains(next) && seen.add(next)) {
                        queue.add(next);
                    }
                }
            }
            if (nearest < bestDistance) {
                bestDistance = nearest;
                best = component;
            }
        }
        return best;
    }

    private static Set<BlockPos> largestComponent(Set<BlockPos> blocks) {
        Set<BlockPos> best = Set.of();
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos start : blocks) {
            if (!seen.add(start)) {
                continue;
            }
            Set<BlockPos> component = new HashSet<>();
            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                BlockPos pos = queue.poll();
                component.add(pos);
                for (BlockPos next : neighbours(pos)) {
                    if (blocks.contains(next) && seen.add(next)) {
                        queue.add(next);
                    }
                }
            }
            if (component.size() > best.size()) {
                best = component;
            }
        }
        return best;
    }

    private record Flood(Set<BlockPos> cells, int waterBlocks, boolean touchesBoundary) {
    }

    private static Flood flood(BlockPos start, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                               Probe probe, Set<BlockPos> visited) {
        Set<BlockPos> cells = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);
        boolean touches = false;
        int water = 0;

        while (!queue.isEmpty() && cells.size() < MAX_CELLS_PER_FILL) {
            BlockPos pos = queue.poll();
            cells.add(pos);
            if (probe.isWater(pos)) {
                water++;
            }
            if (pos.getX() == minX || pos.getX() == maxX
                    || pos.getY() == minY || pos.getY() == maxY
                    || pos.getZ() == minZ || pos.getZ() == maxZ) {
                touches = true;
            }
            for (BlockPos next : neighbours(pos)) {
                if (next.getX() < minX || next.getX() > maxX
                        || next.getY() < minY || next.getY() > maxY
                        || next.getZ() < minZ || next.getZ() > maxZ) {
                    continue;
                }
                if (visited.contains(next) || probe.isSolid(next)) {
                    continue;
                }
                visited.add(next);
                queue.add(next);
            }
        }
        return new Flood(cells, water, touches);
    }

    private static BlockPos[] neighbours(BlockPos pos) {
        return new BlockPos[]{
                pos.above(), pos.below(), pos.north(), pos.south(), pos.east(), pos.west()
        };
    }
}
