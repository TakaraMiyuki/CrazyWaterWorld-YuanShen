package com.opamb.barotrauma.blockentity;

import com.opamb.barotrauma.block.HullBlock;
import com.opamb.barotrauma.network.ModNetwork;
import com.opamb.barotrauma.network.ShipHullDamagePacket;
import com.opamb.barotrauma.registry.ModBlockEntities;
import com.opamb.barotrauma.registry.ModShipBlocks;
import com.opamb.barotrauma.ship.HullDamage;
import com.opamb.barotrauma.ship.MachineCount;
import com.opamb.barotrauma.ship.ShipGeometry;
import com.opamb.barotrauma.ship.ShipState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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
    /** 世界加载时从 NBT 恢复的耐久；重扫时并入（previous 为空的那条路径）。 */
    @Nullable
    private HullDamage pendingDamage;
    @Nullable
    private Float pendingMultiplier;
    private long lastTickGameTime = Long.MIN_VALUE;
    /** 方块船的水面同步计时。 */
    private long lastWaterSyncGameTime = Long.MIN_VALUE;
    /** 船壳耐久同步计时（受击/焊接时立即发，平时低频刷新）。 */
    private long lastHullSyncGameTime = Long.MIN_VALUE;

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
            // P3：方块船也吃海压（深度 = 核心所在深度）
            if (com.opamb.barotrauma.ship.ShipPressure.tick((ServerLevel) level,
                    be.state, -pos.getY())) {
                be.setChanged();
            }
            be.state.simulation().tick(0.1D);
            // 破口进水：连海空腔（船体破洞）按速率缓慢灌水，而不是瞬间满
            be.state.simulation().tickSeaFlood(0.1D, -pos.getY());
            // 还没破块、但伤害 ≥10%/70% 的船壳渗水
            be.state.tickLeaks(0.1D);
            // 开着的门：两侧水位不一样时按限速慢慢流，不做瞬间均摊
            be.state.simulation().tickDoorFlow(0.1D);
            // 方块船移动已关闭（用户实测"方块船太烂：移动时找不到声纳、乱闪"）。
            // 主路径是 VS2 物理船（`/barotrauma ship vs launch`）。代码保留，以后做
            // "服务端整格 + 客户端插值"的自研移动层时再启用。
            // 不再把水位写成 MC 水方块：那会造成"无限水/海绵无效"，
            // 而且物理船体积内被流体拦截挡住也写不进去。水面由客户端自绘。
            // 每 40 tick 检查船体有没有破洞；破了就重新分舱（破口舱室会变成"连海"并灌满）
            if (now % 40L == 0L && !be.state.hullIntact(level)) {
                be.rescan(level);
                com.opamb.barotrauma.BarotraumaMod.LOGGER.info(
                        "[ship] 方块船船体破损，已重扫：舱室 {}｜连海 {}｜连海水 {} 单位",
                        be.state.compartments().size(),
                        be.state.geometry().openPockets().size(),
                        Math.round(be.state.simulation().pocketWaterTotal()));
            }
            // 方块船也要把水面状态发给客户端（物理船走 VsPilot 的同步）。
            // 和模拟步进一样每 2 tick（10 Hz）发一次，客户端插值才不会看到台阶。
            if (now % 2L == 0L && now != be.lastWaterSyncGameTime) {
                be.lastWaterSyncGameTime = now;
                com.opamb.barotrauma.network.ModNetwork.sendToAll(
                        com.opamb.barotrauma.ship.ShipWaterStateBuilder.build(
                                be.state, -1L, false, 0.0F, 0.0F, 0.0F));
            }
            // 船壳耐久：没受损时不发；有受损就低频刷新，受击/焊接时 force 立即发。
            // 第一次 tick 必须发一次（哪怕是空包），否则客户端可能留着上一艘船的血条。
            be.sendHullDamage(level, be.lastHullSyncGameTime == Long.MIN_VALUE);
        }
    }

    // ------------------------------------------------------------------ 船壳耐久

    /**
     * 左键砸船壳：先扣耐久，只有耐久到 0 才真的破口。
     *
     * @return true 表示这一格属于本船、已经由这里接管（调用方要取消原版破坏）
     */
    public boolean damageBlock(ServerLevel level, BlockPos pos, float amount,
                               @Nullable ServerPlayer player) {
        ShipState current = state;
        if (current == null) {
            return false;
        }
        BlockState block = level.getBlockState(pos);
        float max = current.hullMaxHealth(block);
        if (max <= 0.0F || !current.tracksHullBlock(pos)) {
            return false;
        }
        // 创造模式蹲下 = 建造模式：0% 的 breached 方块直接清掉（否则没有碰撞后
        // 再也挖不掉，只能焊回去）；普通方块仍然一击打到 0%。
        if (player != null && player.isCreative() && player.isShiftKeyDown()
                && HullBlock.isBreached(block)) {
            current.hullDamage().clear(pos);
            current.removeBreach(pos);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            ShipState next = rescan(level);
            next.removeBreach(pos);
            setChanged();
            sendHullDamage(level, true);
            return true;
        }
        // 创造模式蹲下 = 建造模式，一击打到 0%（breached，不会真删除）。
        if (player != null && player.isCreative() && player.isShiftKeyDown()) {
            amount = max * 2.0F;
        }
        float health = current.hullDamage().applyDamage(pos, amount, max);
        if (health <= 0.0F) {
            breachBlock(level, pos, block, player);
        } else {
            hitFeedback(level, pos, player);
            if (player != null) {
                // 创造模式客户端会预测“方块没了”，取消破坏后必须把真实状态发回去。
                player.connection.send(new ClientboundBlockUpdatePacket(level, pos));
            }
            setChanged();
            sendHullDamage(level, true);
        }
        return true;
    }

    /**
     * 焊接工具修一格；如果这格是 0% 的 breached，修到 >0 时恢复实体和碰撞。
     */
    public boolean repairBlock(ServerLevel level, BlockPos pos, float amount,
                               @Nullable ServerPlayer player) {
        ShipState current = state;
        if (current == null) {
            return false;
        }
        BlockState block = level.getBlockState(pos);
        float max = current.hullMaxHealth(block);
        if (max <= 0.0F || !current.tracksHullBlock(pos) || !current.hullDamage().isDamaged(pos)) {
            return false;
        }
        boolean wasBreached = HullBlock.isBreached(block);
        float health = current.hullDamage().repair(pos, amount, max);
        level.sendParticles(ParticleTypes.SMOKE,
                pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                3, 0.25D, 0.25D, 0.25D, 0.01D);
        if (player != null && level.getGameTime() % 10L == 0L) {
            level.playSound(null, pos, SoundEvents.FIRE_AMBIENT, SoundSource.PLAYERS, 0.35F, 1.7F);
        }
        if (wasBreached && health > 0.0F) {
            // 修过 0%：先撤掉破口记录，再恢复实体/碰撞并重扫封口。
            current.removeBreach(pos);
            level.setBlock(pos, block.setValue(HullBlock.BREACHED, false), 3);
            rescan(level);
        }
        setChanged();
        sendHullDamage(level, true);
        return true;
    }

    /**
     * 爆炸：对给定范围内的船体格子按距离衰减扣耐久，最后统一重扫一次。
     * 返回实际受到伤害的格数；调用方应把这些格子从爆炸的破坏列表里移除。
     */
    public int applyExplosion(ServerLevel level, Collection<BlockPos> positions,
                              Vec3 center, double radius, float baseAmount) {
        ShipState current = state;
        if (current == null || positions.isEmpty() || radius <= 0.0D) {
            return 0;
        }
        List<BlockPos> broken = new ArrayList<>();
        int hit = 0;
        for (BlockPos pos : positions) {
            BlockState block = level.getBlockState(pos);
            float max = current.hullMaxHealth(block);
            if (max <= 0.0F || !current.tracksHullBlock(pos)) {
                continue;
            }
            double distance = Math.sqrt(Vec3.atCenterOf(pos).distanceToSqr(center));
            if (distance > radius) {
                continue;
            }
            float amount = (float) (baseAmount * (1.0D - distance / radius));
            if (amount <= 0.0F) {
                continue;
            }
            hit++;
            if (current.hullDamage().applyDamage(pos, amount, max) <= 0.0F) {
                broken.add(pos);
            }
        }
        List<BlockPos> breached = new ArrayList<>();
        for (BlockPos pos : broken) {
            BlockState block = level.getBlockState(pos);
            if (block.getBlock() instanceof HullBlock) {
                current.hullDamage().setHealth(pos, 0.0F);
                level.setBlock(pos, block.setValue(HullBlock.BREACHED, true), 3);
                breached.add(pos);
            } else {
                current.hullDamage().clear(pos);
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
        if (hit > 0) {
            if (!breached.isEmpty()) {
                ShipState next = rescan(level);
                next.addBreaches(breached);
            } else if (!broken.isEmpty()) {
                rescan(level);
            }
            setChanged();
            sendHullDamage(level, true);
        }
        return hit;
    }

    /**
     * 耐久到 0：方块不删，切成 {@code breached}（无碰撞、不可见、保留数据）。
     * 重扫后这一格对几何来说就是空气，舱室会变成连海空腔，水/气泡走现有链路。
     */
    private void breachBlock(ServerLevel level, BlockPos pos, BlockState block,
                             @Nullable ServerPlayer player) {
        if (!(block.getBlock() instanceof HullBlock)) {
            // 理论上不会发生；保留一个兜底，避免旧存档里的异常方块卡住 0% 状态。
            if (state != null) {
                state.hullDamage().clear(pos);
            }
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            rescan(level);
            return;
        }
        if (state != null) {
            state.hullDamage().setHealth(pos, 0.0F);
        }
        level.setBlock(pos, block.setValue(HullBlock.BREACHED, true), 3);
        level.playSound(null, pos, SoundEvents.NETHERITE_BLOCK_BREAK,
                SoundSource.PLAYERS, 0.8F, 0.9F);
        ShipState next = rescan(level);
        next.addBreaches(List.of(pos));
        if (player != null) {
            player.displayClientMessage(Component.translatable("message.barotrauma.hull_broken")
                    .withStyle(ChatFormatting.RED), true);
        }
        setChanged();
        sendHullDamage(level, true);
    }

    private void hitFeedback(ServerLevel level, BlockPos pos, @Nullable ServerPlayer player) {
        level.sendParticles(ParticleTypes.CRIT,
                pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                6, 0.3D, 0.3D, 0.3D, 0.02D);
        level.playSound(null, pos, SoundEvents.NETHERITE_BLOCK_HIT, SoundSource.PLAYERS, 0.6F, 1.2F);
    }

    /** 受击/焊接时 force=true；平时 10 tick 一次，没受损就不发包。 */
    private void sendHullDamage(Level level, boolean force) {
        if (state == null) {
            return;
        }
        boolean hasDamage = !state.hullDamage().isEmpty();
        if (!hasDamage && !force) {
            return;
        }
        // 修满/打爆后必须发一次空包，客户端才能把条清掉；空包不受节流限制。
        long now = level.getGameTime();
        long interval = hasDamage ? (force ? 2L : 10L) : 0L;
        if (interval > 0L && lastHullSyncGameTime != Long.MIN_VALUE
                && now - lastHullSyncGameTime < interval) {
            return;
        }
        lastHullSyncGameTime = now;
        ModNetwork.sendToAll(ShipHullDamagePacket.build(state, -1L, false, 0.0F, 0.0F, 0.0F));
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
        // 先记下旧船体里已经消失的格子：新几何扫完后它们不再是 hull，
        // 但客户端需要这些坐标在洞口画水束/气泡。
        List<BlockPos> broken = new ArrayList<>();
        ShipState previous = this.state;
        if (previous != null) {
            for (BlockPos pos : previous.geometry().hull()) {
                if (!ModShipBlocks.isShipBlock(level.getBlockState(pos))) {
                    broken.add(pos);
                }
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

            @Override
            public boolean isDoorway(BlockPos pos) {
                return ModShipBlocks.isDoorway(level.getBlockState(pos));
            }

            @Override
            public boolean isOpenDoorway(BlockPos pos) {
                return ModShipBlocks.isOpenDoorway(level.getBlockState(pos));
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
        // 船壳耐久是逐格记录：重扫换了 ShipState 对象，必须把旧记录迁过来，
        // 再把已经打没/拆掉的格子清掉，否则修船进度会凭空重置。
        if (previous != null) {
            state.hullDamage().copyFrom(previous.hullDamage());
            state.setHullHealthMultiplier(previous.hullHealthMultiplier());
            state.hullDamage().retain(pos -> ModShipBlocks.isRepairableHull(level.getBlockState(pos)));
            // 开合门也会重扫：水量必须按格子搬过来，否则一关门水就全跑到漏水侧。
            state.inheritWaterFrom(previous);
        } else if (pendingDamage != null) {
            // 世界重开后的第一次重扫：把区块 NBT 里存下来的耐久接回来，
            // 否则 10%~90% 的部分损伤会全部凭空满血。
            state.hullDamage().copyFrom(pendingDamage);
            state.setHullHealthMultiplier(pendingMultiplier != null ? pendingMultiplier : 1.0F);
            state.hullDamage().retain(pos -> ModShipBlocks.isRepairableHull(level.getBlockState(pos)));
        }
        pendingDamage = null;
        pendingMultiplier = null;
        // breached 方块仍然是 ship block（hull 集合里有它），但不再实心；
        // 重新加载后耐久表是空的，必须按 blockstate 补回 0%，否则焊不回去。
        List<BlockPos> breached = new ArrayList<>();
        for (BlockPos hullPos : geometry.hull()) {
            if (HullBlock.isBreached(level.getBlockState(hullPos))) {
                state.hullDamage().setHealth(hullPos, 0.0F);
                breached.add(hullPos);
            }
        }
        // 旧破口要带过来：新扫描的 hull 已经不包含它们，只靠 broken 会丢掉之前的洞。
        if (previous != null) {
            List<BlockPos> oldBreaches = new ArrayList<>(previous.breaches().size());
            for (ShipState.Breach breach : previous.breaches()) {
                oldBreaches.add(breach.pos());
            }
            state.addBreaches(oldBreaches);
        }
        state.addBreaches(broken);
        state.addBreaches(breached);
        // 洞越多进水越快：被删掉的格 + 0% breached 格。
        state.simulation().setBreachSize(Math.max(1, broken.size() + breached.size()));
        // 重扫可能把“已经被别的东西弄没的方块”从耐久表里清掉；必须发一次，
        // 否则客户端会在空气位置继续画旧血条。
        setChanged();
        sendHullDamage(level, true);
        return state;
    }

    @Nullable
    public ShipState state() {
        return state;
    }

    // ------------------------------------------------------------------ 持久化

    /** 方块船的耐久随区块存档：世界重开后 partial damage 不再清零。 */
    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (state != null) {
            tag.put("BarotraumaHullDamage", state.hullDamage().saveNbt());
            tag.putFloat("BarotraumaHullMultiplier", state.hullHealthMultiplier());
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("BarotraumaHullDamage", Tag.TAG_LIST)) {
            HullDamage restored = new HullDamage();
            restored.loadNbt(tag.getList("BarotraumaHullDamage", Tag.TAG_COMPOUND));
            pendingDamage = restored;
        }
        pendingMultiplier = tag.contains("BarotraumaHullMultiplier")
                ? tag.getFloat("BarotraumaHullMultiplier") : null;
    }
}
