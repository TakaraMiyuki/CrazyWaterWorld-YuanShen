package com.opamb.barotrauma.blockentity;

import com.opamb.barotrauma.registry.ModBlockEntities;
import com.opamb.barotrauma.registry.ModShipBlocks;
import com.opamb.barotrauma.ship.MachineCount;
import com.opamb.barotrauma.ship.ShipGeometry;
import com.opamb.barotrauma.ship.ShipState;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 船核心方块实体：持有船体扫描结果。
 *
 * <p>扫描半径 24 格——比常见潜艇尺寸（24×20×40）略大，能把整艘船包住。</p>
 */
public class ShipCoreBlockEntity extends BlockEntity {

    /** 扫描半径（格）。 */
    public static final int RADIUS = 24;

    @Nullable
    private ShipState state;
    private long lastTickGameTime = Long.MIN_VALUE;

    public ShipCoreBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.SHIP_CORE.get(), pos, blockState);
    }

    /** 服务端 tick：每 2 tick 推进一次模拟（0.1 s，即 10 Hz）。 */
    public static void serverTick(net.minecraft.world.level.Level level, BlockPos pos, BlockState blockState,
                                  ShipCoreBlockEntity be) {
        if (be.isRemoved()) {
            return;
        }
        long now = level.getGameTime();
        if (be.lastTickGameTime != Long.MIN_VALUE && now - be.lastTickGameTime < 2L) {
            return;
        }
        be.lastTickGameTime = now;
        if (be.state != null) {
            be.state.simulation().tick(0.1D);
            // 驾驶推进（内部按功率节流）
            be.state.tickMovement((net.minecraft.server.level.ServerLevel) level, pos);
            // 船挪走之后这一格已经不是核心了：立刻收手，否则旧状态会把水写回船原来的位置
            if (be.isRemoved() || be.state == null) {
                return;
            }
            // 物理船（VS2）激活时，船体方块已经进了刚体、不在世界里了：再往世界写水
            // 就会灌到船原来的位置，hullIntact 也永远查不到方块 → 反复重扫、舱室被判成
            // "连海"、然后一直进水。这就是"改成瓦尔基里会漏水"的成因。
            boolean physical = com.opamb.barotrauma.ship.vs.VsSupport.available()
                    && com.opamb.barotrauma.ship.vs.VsPilot.hasShip();
            // 每 10 tick 把水位写成真实水方块（不用每 tick 改方块）
            if (!physical && now % 10L == 0L) {
                be.state.applyWater(level);
            }
            // 每 40 tick 检查船体有没有破洞；破了就重新分舱（破口舱室会变成"连海"并灌满）
            if (!physical && now % 40L == 0L && !be.state.hullIntact(level)) {
                be.rescan(level);
            }
        }
    }

    /** 直接接管一份船状态（推进时用平移后的状态，免重扫）。 */
    public void setState(ShipState state) {
        this.state = state;
    }

    /** 重新扫描船体几何与机器，返回最新的船状态。 */
    public ShipState rescan(Level level) {
        BlockPos corePos = worldPosition;
        ShipGeometry.Probe probe = new ShipGeometry.Probe() {
            @Override
            public boolean isSolid(BlockPos pos) {
                if (pos.equals(corePos)) {
                    // 核心自身占一格，不能把自己当成墙，否则洪水填充从起点就走不动
                    return false;
                }
                BlockState state = level.getBlockState(pos);
                return ModShipBlocks.isSolidForShip(state);
            }

            @Override
            public boolean isWater(BlockPos pos) {
                return level.getBlockState(pos).getFluidState().is(FluidTags.WATER);
            }
        };
        ShipGeometry.Result geometry = ShipGeometry.scanShip(corePos, RADIUS,
                pos -> ModShipBlocks.isShipBlock(level.getBlockState(pos)), probe);

        int reactor = 0;
        int oxygen = 0;
        int engine = 0;
        int pump = 0;
        int ballast = 0;
        int airlock = 0;
        int sonar = 0;
        int helm = 0;
        if (geometry.hullBlocks() > 0) {
            ShipGeometry.Bounds bounds = geometry.bounds();
            for (BlockPos pos : BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
                switch (ModShipBlocks.machineOf(level.getBlockState(pos))) {
                    case REACTOR -> reactor++;
                    case OXYGEN -> oxygen++;
                    case ENGINE -> engine++;
                    case PUMP -> pump++;
                    case BALLAST -> ballast++;
                    case AIRLOCK -> airlock++;
                    case SONAR -> sonar++;
                    case HELM -> helm++;
                    default -> {
                    }
                }
            }
        }

        state = new ShipState(geometry,
                new MachineCount(reactor, oxygen, engine, pump, ballast, airlock, sonar, helm));
        return state;
    }

    @Nullable
    public ShipState state() {
        return state;
    }
}
