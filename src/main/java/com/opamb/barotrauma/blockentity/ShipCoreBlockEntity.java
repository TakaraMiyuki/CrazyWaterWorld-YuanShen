package com.opamb.barotrauma.blockentity;

import com.opamb.barotrauma.registry.ModBlockEntities;
import com.opamb.barotrauma.registry.ModShipBlocks;
import com.opamb.barotrauma.ship.MachineCount;
import com.opamb.barotrauma.ship.ShipGeometry;
import com.opamb.barotrauma.ship.ShipState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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
    /** 方块船的水面同步计时。 */
    private long lastWaterSyncGameTime = Long.MIN_VALUE;

    /**
     * 子类（声纳）用的构造：传入自己真实的方块实体类型。
     *
     * <p>声纳台现在是船脑（{@code SonarBlockEntity extends ShipCoreBlockEntity}），
     * 它原封不动继承这里的扫描、模拟、推进逻辑。{@code BlockEntity} 的类型必须在构造时
     * 给定，而子类的类型不是 SHIP_CORE，所以需要这一层。</p>
     */
    protected ShipCoreBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type,
                                  BlockPos pos, BlockState blockState) {
        super(type, pos, blockState);
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
        // 这艘船的方块是不是已经被收进 VS2 刚体了。方块进刚体之后并没有消失，
        // 而是被搬到船坞区块里、**仍然在 tick**——所以这里必须显式停手，否则：
        //   · tickMovement 会把船坞里的方块自己搬来搬去，和物理那套叠在一起
        //     （用户看到的就是"方块移动和物理移动叠加"）；
        //   · applyWater 把水写进船坞里的"船舱"，看起来就是莫名其妙进水；
        //   · hullIntact 在船坞坐标下永远对不上，触发无限重扫 → 舱室被判成"连海" → 灌满。
        if (level instanceof ServerLevel server
                && com.opamb.barotrauma.ship.vs.VsState.isPhysical(server, pos)) {
            // 这个方块实体现在活在船坞区块里。把它的真实坐标报给 VsPilot，
            // 才能算出"原世界坐标 → 船坞坐标"的平移量（破洞/砸方块全靠它）。
            com.opamb.barotrauma.ship.vs.VsState.observePhysicalCore(server, pos);
            return;
        }
        if (be.state != null) {
            be.state.simulation().tick(0.1D);
            // 破口进水：连海空腔（船体破洞）按速率缓慢灌水，而不是瞬间满
            be.state.simulation().tickSeaFlood(0.1D, -pos.getY());
            // 方块船移动已关闭（用户实测"方块船太烂：移动时找不到声纳、乱闪"）。
            // 主路径是 VS2 物理船（`/barotrauma ship vs launch`）。代码保留，以后做
            // "服务端整格 + 客户端插值"的自研移动层时再启用。
            // 不再把水位写成 MC 水方块：那会造成"无限水/海绵无效"，
            // 而且物理船体积内被流体拦截挡住也写不进去。水面由客户端自绘。
            // 每 40 tick 检查船体有没有破洞；破了就重新分舱（破口舱室会变成"连海"并灌满）
            if (now % 40L == 0L && !be.state.hullIntact(level)) {
                be.rescan(level);
            }
            // 方块船也要把水面状态发给客户端（物理船走 VsPilot 的同步）
            if (now % 10L == 0L && now != be.lastWaterSyncGameTime) {
                be.lastWaterSyncGameTime = now;
                com.opamb.barotrauma.network.ModNetwork.sendToAll(
                        com.opamb.barotrauma.ship.ShipWaterStateBuilder.build(
                                be.state, -1L, false, 0.0F, 0.0F, 0.0F));
            }
        }
    }

    /** 直接接管一份船状态（推进时用平移后的状态，免重扫）。 */
    public void setState(ShipState state) {
        this.state = state;
    }

    /** 重新扫描船体几何与机器，返回最新的船状态。 */
    public ShipState rescan(Level level) {
        // 这艘船的方块已经进了 VS2 刚体：按船坞坐标扫出来的是船坞里的几何，全是垃圾。
        // 直接代理到装配时那份船状态（同一个对象），声纳面板 / 操舵就都还是对的。
        if (level instanceof net.minecraft.server.level.ServerLevel server
                && com.opamb.barotrauma.ship.vs.VsState.isPhysical(server, worldPosition)) {
            ShipState active = com.opamb.barotrauma.ship.vs.VsState.activeState();
            if (active != null) {
                this.state = active;
                return active;
            }
        }
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

        int sonar = 0;
        if (geometry.hullBlocks() > 0) {
            ShipGeometry.Bounds bounds = geometry.bounds();
            for (BlockPos pos : BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
                if (ModShipBlocks.machineOf(level.getBlockState(pos)) == ModShipBlocks.Machine.SONAR) {
                    sonar++;
                }
            }
        }

        // 机器方块已全部删除，MachineCount 里只有声纳还有意义（保持信号接口不变）
        state = new ShipState(geometry, new MachineCount(0, 0, 0, 0, 0, 0, sonar));
        return state;
    }

    @Nullable
    public ShipState state() {
        return state;
    }
}
