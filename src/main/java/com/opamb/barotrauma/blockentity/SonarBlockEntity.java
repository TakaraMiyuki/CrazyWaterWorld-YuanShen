package com.opamb.barotrauma.blockentity;

import com.opamb.barotrauma.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 声纳方块实体 —— 同时也是**船脑**。
 *
 * <p>从这一版开始，船核心不再是一个独立的方块：{@code SonarBlockEntity} 直接继承
 * {@link ShipCoreBlockEntity}，完整继承了船体扫描、分舱、水位模拟、推进这套逻辑。
 * 这样声纳方块天然就是 {@code isShipBlock}，会被 {@code collectBlocks} 正常收进
 * VS2 刚体，不会再出现"核心留在世界里把整艘船顶死"的问题。</p>
 *
 * <p>另外它还有自己的本职工作：以声纳台为中心、{@link #RADIUS} 格为半径做点云扫描，
 * 交给渲染器画成悬浮全息。</p>
 */
public class SonarBlockEntity extends ShipCoreBlockEntity {

    /** 点云扫描半径（格）。 */
    public static final int RADIUS = 40;
    /** 采样步长。 */
    public static final int STRIDE = 4;
    /** 点云上限，超了按比例抽稀。 */
    private static final int MAX_POINTS = 2400;
    /** 重新扫描间隔（tick）。 */
    private static final int SCAN_INTERVAL = 20;

    private final List<BlockPos> points = new ArrayList<>();
    private long lastScan = Long.MIN_VALUE;

    public SonarBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SONAR.get(), pos, state);
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, SonarBlockEntity be) {
        long now = level.getGameTime();
        if (now - be.lastScan < SCAN_INTERVAL) {
            return;
        }
        be.lastScan = now;
        be.scan(level, pos);
    }

    private void scan(Level level, BlockPos center) {
        points.clear();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -RADIUS; dx <= RADIUS; dx += STRIDE) {
            for (int dy = -RADIUS; dy <= RADIUS; dy += STRIDE) {
                for (int dz = -RADIUS; dz <= RADIUS; dz += STRIDE) {
                    double distSq = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                    if (distSq > (double) RADIUS * RADIUS) {
                        continue;
                    }
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (level.isOutsideBuildHeight(cursor)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir() || state.is(Blocks.WATER)) {
                        continue;
                    }
                    points.add(cursor.immutable());
                }
            }
        }
        if (points.size() > MAX_POINTS) {
            int keep = MAX_POINTS;
            int step = (int) Math.ceil(points.size() / (double) keep);
            List<BlockPos> thinned = new ArrayList<>(keep);
            for (int i = 0; i < points.size(); i += step) {
                thinned.add(points.get(i));
            }
            points.clear();
            points.addAll(thinned);
        }
    }

    public List<BlockPos> points() {
        return points;
    }
}
