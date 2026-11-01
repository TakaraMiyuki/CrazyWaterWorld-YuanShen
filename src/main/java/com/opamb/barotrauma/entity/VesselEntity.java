package com.opamb.barotrauma.entity;

import com.opamb.barotrauma.network.ModNetwork;
import com.opamb.barotrauma.network.VesselPalettePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构体船：整艘船的方块收进这个实体里，方块数据在 NBT，位置由实体自己推进。
 *
 * <p>M4 原型：没有逐方块碰撞，用船体包围盒的采样点做阻挡判断；也没有渲染同步以外的花活。
 * 客户端渲染需要的调色板通过 {@link VesselPalettePacket} 在实体进入视野时下发。</p>
 */
public class VesselEntity extends Entity {

    private final List<BlockState> palette = new ArrayList<>();
    private final List<BlockPos> offsets = new ArrayList<>();
    private int sizeX = 1;
    private int sizeY = 1;
    private int sizeZ = 1;
    private Vec3 thrust = Vec3.ZERO;
    private Vec3 motion = Vec3.ZERO;

    public VesselEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    // ------------------------------------------------------------------
    // 数据
    // ------------------------------------------------------------------

    public void setPalette(List<BlockState> states, List<BlockPos> localOffsets, int sx, int sy, int sz) {
        palette.clear();
        palette.addAll(states);
        offsets.clear();
        offsets.addAll(localOffsets);
        sizeX = Math.max(1, sx);
        sizeY = Math.max(1, sy);
        sizeZ = Math.max(1, sz);
    }

    public List<BlockState> palette() {
        return palette;
    }

    public List<BlockPos> offsets() {
        return offsets;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public void setThrust(Vec3 value) {
        thrust = value;
    }

    public Vec3 thrust() {
        return thrust;
    }

    // ------------------------------------------------------------------
    // tick
    // ------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            return;
        }
        if (thrust.lengthSqr() > 1.0E-4D) {
            motion = motion.add(thrust.scale(0.02D)).scale(0.96D);
        } else {
            motion = motion.scale(0.90D);
        }
        if (motion.lengthSqr() < 1.0E-6D) {
            motion = Vec3.ZERO;
            return;
        }
        Vec3 next = position().add(motion);
        if (canOccupy(next)) {
            setPos(next.x, next.y, next.z);
        } else {
            motion = Vec3.ZERO;
        }
    }

    /** 船体包围盒的采样点是否都空。 */
    private boolean canOccupy(Vec3 base) {
        int[][] samples = {
                {0, 0, 0}, {sizeX, 0, 0}, {0, 0, sizeZ}, {sizeX, 0, sizeZ},
                {0, sizeY, 0}, {sizeX, sizeY, 0}, {0, sizeY, sizeZ}, {sizeX, sizeY, sizeZ},
                {sizeX / 2, sizeY / 2, sizeZ / 2}
        };
        for (int[] sample : samples) {
            BlockPos pos = BlockPos.containing(base.x + sample[0], base.y + sample[1], base.z + sample[2]);
            BlockState state = level().getBlockState(pos);
            if (!state.isAir() && state.getFluidState().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 同步：进入玩家视野时下发调色板
    // ------------------------------------------------------------------

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        if (palette.isEmpty()) {
            return;
        }
        ModNetwork.sendToPlayer(player, new VesselPalettePacket(getId(), palette, offsets, sizeX, sizeY, sizeZ));
    }

    // ------------------------------------------------------------------
    // 存档
    // ------------------------------------------------------------------

    @Override
    protected void defineSynchedData() {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        palette.clear();
        offsets.clear();
        ListTag list = tag.getList("palette", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            palette.add(NbtUtils.readBlockState(
                    level().holderLookup(net.minecraft.core.registries.Registries.BLOCK),
                    entry.getCompound("state")));
            CompoundTag pos = entry.getCompound("pos");
            offsets.add(new BlockPos(pos.getInt("x"), pos.getInt("y"), pos.getInt("z")));
        }
        sizeX = Math.max(1, tag.getInt("sx"));
        sizeY = Math.max(1, tag.getInt("sy"));
        sizeZ = Math.max(1, tag.getInt("sz"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        ListTag list = new ListTag();
        for (int i = 0; i < palette.size(); i++) {
            CompoundTag entry = new CompoundTag();
            entry.put("state", NbtUtils.writeBlockState(palette.get(i)));
            BlockPos offset = offsets.get(i);
            CompoundTag pos = new CompoundTag();
            pos.putInt("x", offset.getX());
            pos.putInt("y", offset.getY());
            pos.putInt("z", offset.getZ());
            entry.put("pos", pos);
            list.add(entry);
        }
        tag.put("palette", list);
        tag.putInt("sx", sizeX);
        tag.putInt("sy", sizeY);
        tag.putInt("sz", sizeZ);
    }

    /** 展开成真实方块。 */
    public int deploy() {
        int placed = 0;
        for (int i = 0; i < palette.size(); i++) {
            BlockPos offset = offsets.get(i);
            BlockPos target = BlockPos.containing(position().x + offset.getX(), position().y + offset.getY(),
                    position().z + offset.getZ());
            level().setBlock(target, palette.get(i), 3);
            placed++;
        }
        return placed;
    }

    public static BlockState air() {
        return Blocks.AIR.defaultBlockState();
    }
}
