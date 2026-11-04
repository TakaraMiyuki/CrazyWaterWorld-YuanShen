package com.opamb.barotrauma.data;

import com.opamb.barotrauma.ship.HullDamage;
import com.opamb.barotrauma.ship.ShipState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * 深海维度的持久化记录：世界重开后把船"接回来"需要的一切。
 *
 * <p>存三类东西：</p>
 * <ul>
 *   <li>{@code starterClaimed}——初始潜艇是否已经发过船。它可能被装配走、靠泊在别处，
 *       {@link com.opamb.barotrauma.world.StarterSub} 看到这个标记就不再在原位凭空造第二艘；</li>
 *   <li>{@link AssembledShip}——物理船的认领记录（VS2 ship id + 装配快照 + 耐久）。
 *       世界加载后按它重挂推进器、重建船状态（P1.2 的认领路径）；</li>
 *   <li>方块船的耐久走 {@code ShipCoreBlockEntity} 自己的 NBT（随区块存档），不经过这里。</li>
 * </ul>
 *
 * <p>坐标空间约定：{@link AssembledShip} 里的所有坐标都是**装配快照坐标**
 * （装配那一刻的世界坐标）。靠泊时按整数位移还原；认领时用它和 VS2 刚体做匹配。</p>
 */
public final class ShipSavedData extends SavedData {

    public static final String NAME = "barotrauma_ships";

    private boolean starterClaimed;

    @Nullable
    private AssembledShip assembled;

    public static ShipSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                tag -> load(tag, level), ShipSavedData::new, NAME);
    }

    public boolean starterClaimed() {
        return starterClaimed;
    }

    public void markStarterClaimed() {
        if (!starterClaimed) {
            starterClaimed = true;
            setDirty();
        }
    }

    @Nullable
    public AssembledShip assembled() {
        return assembled;
    }

    public void setAssembled(AssembledShip record) {
        this.assembled = record;
        setDirty();
    }

    public void clearAssembled() {
        if (assembled != null) {
            assembled = null;
            setDirty();
        }
    }

    // ------------------------------------------------------------------ 记录

    public static final class AssembledShip {

        public long vsShipId;
        /** 装配时刚体中心（快照坐标空间）。 */
        public double x;
        public double y;
        public double z;
        /** 声纳核心的装配坐标。 */
        public BlockPos corePos = BlockPos.ZERO;
        /** 声纳交互代理方块的世界坐标（跟船移动；认领时恢复用）。 */
        public BlockPos proxyPos = BlockPos.ZERO;
        public float hullMultiplier = 1.0F;
        /** 装配时的船体格数：认领后重算破口大小（destroyed 口径）的基准；0 = 旧存档未知，按当前快照大小兜底。 */
        public int initialBlocks;
        /** 最近一次同步时的刚体位置（仅用于认领的位置回退匹配；不是快照坐标空间，勿与 x/y/z 混用）。 */
        public double lastX;
        public double lastY;
        public double lastZ;
        /** 装配快照；破损对账/焊接会改它，每 5 秒随 tick 同步进来。 */
        public final Map<Long, BlockState> snapshot = new HashMap<>();
        public final HullDamage hullDamage = new HullDamage();

        public static AssembledShip of(long shipId, BlockPos core, BlockPos proxy, Vec3 pos,
                                       Map<BlockPos, BlockState> blocks, ShipState shipState) {
            AssembledShip ship = new AssembledShip();
            ship.vsShipId = shipId;
            ship.corePos = core.immutable();
            ship.proxyPos = proxy == null ? core.immutable() : proxy.immutable();
            ship.x = pos.x;
            ship.y = pos.y;
            ship.z = pos.z;
            ship.lastX = pos.x;
            ship.lastY = pos.y;
            ship.lastZ = pos.z;
            ship.initialBlocks = blocks.size();
            for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
                ship.snapshot.put(entry.getKey().asLong(), entry.getValue());
            }
            if (shipState != null) {
                ship.hullMultiplier = shipState.hullHealthMultiplier();
                ship.hullDamage.copyFrom(shipState.hullDamage());
            }
            return ship;
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("shipId", vsShipId);
            tag.putDouble("x", x);
            tag.putDouble("y", y);
            tag.putDouble("z", z);
            tag.putDouble("lastX", lastX);
            tag.putDouble("lastY", lastY);
            tag.putDouble("lastZ", lastZ);
            tag.putInt("initialBlocks", initialBlocks);
            tag.putLong("core", corePos.asLong());
            tag.putLong("proxy", proxyPos.asLong());
            tag.putFloat("multiplier", hullMultiplier);
            ListTag snap = new ListTag();
            for (Map.Entry<Long, BlockState> entry : snapshot.entrySet()) {
                CompoundTag item = new CompoundTag();
                item.putLong("pos", entry.getKey());
                item.put("state", NbtUtils.writeBlockState(entry.getValue()));
                snap.add(item);
            }
            tag.put("snapshot", snap);
            tag.put("hullDamage", hullDamage.saveNbt());
            return tag;
        }

        public static AssembledShip load(CompoundTag tag, HolderGetter<Block> blocks) {
            AssembledShip ship = new AssembledShip();
            ship.vsShipId = tag.getLong("shipId");
            ship.x = tag.getDouble("x");
            ship.y = tag.getDouble("y");
            ship.z = tag.getDouble("z");
            // 旧存档没有 lastX/Y/Z：回退到装配位置（老数据本来就只有这个位置可用）
            ship.lastX = tag.contains("lastX") ? tag.getDouble("lastX") : ship.x;
            ship.lastY = tag.contains("lastY") ? tag.getDouble("lastY") : ship.y;
            ship.lastZ = tag.contains("lastZ") ? tag.getDouble("lastZ") : ship.z;
            // 旧存档没有 initialBlocks（0）：认领时按当前快照大小兜底
            ship.initialBlocks = tag.getInt("initialBlocks");
            ship.corePos = BlockPos.of(tag.getLong("core"));
            ship.proxyPos = BlockPos.of(tag.getLong("proxy"));
            ship.hullMultiplier = tag.getFloat("multiplier");
            ListTag snap = tag.getList("snapshot", Tag.TAG_COMPOUND);
            for (int i = 0; i < snap.size(); i++) {
                CompoundTag item = snap.getCompound(i);
                ship.snapshot.put(item.getLong("pos"),
                        NbtUtils.readBlockState(blocks, item.getCompound("state")));
            }
            ship.hullDamage.loadNbt(tag.getList("hullDamage", Tag.TAG_COMPOUND));
            return ship;
        }
    }

    // ------------------------------------------------------------------ NBT

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean("starterClaimed", starterClaimed);
        if (assembled != null) {
            tag.put("assembled", assembled.save());
        }
        return tag;
    }

    private static ShipSavedData load(CompoundTag tag, ServerLevel level) {
        ShipSavedData data = new ShipSavedData();
        data.starterClaimed = tag.getBoolean("starterClaimed");
        if (tag.contains("assembled", Tag.TAG_COMPOUND)) {
            HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
            data.assembled = AssembledShip.load(tag.getCompound("assembled"), blocks);
        }
        return data;
    }
}
