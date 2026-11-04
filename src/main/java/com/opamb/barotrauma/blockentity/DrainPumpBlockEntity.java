package com.opamb.barotrauma.blockentity;

import com.opamb.barotrauma.registry.ModBlockEntities;
import com.opamb.barotrauma.ship.ShipCores;
import com.opamb.barotrauma.ship.ShipState;
import com.opamb.barotrauma.ship.vs.VsState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 排水泵方块实体：保存开关与排水量，并每 tick 把设置推给所在船的 {@link ShipState}。
 *
 * <p>船状态解析：物理船激活时用 {@link VsState#activeState()}（方块在船坞里，
 * 找不到正常的核心）；方块船只找 32 格内的船核心。找不到船就空转。</p>
 */
public class DrainPumpBlockEntity extends BlockEntity {

    /** 排水量档位（单位/秒）：1 格 = 100 单位。 */
    public static final int[] RATES = {300, 600, 1200, 2400};

    private boolean enabled = true;
    private int rate = 600;

    public DrainPumpBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DRAIN_PUMP.get(), pos, state);
    }

    public boolean enabled() {
        return enabled;
    }

    public int rate() {
        return rate;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        setChanged();
    }

    /** 只在预置档位里取值，避免客户端改包塞任意值。 */
    public void setRate(int rate) {
        this.rate = Math.max(100, Math.min(3000, rate));
        setChanged();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  DrainPumpBlockEntity be) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        ShipState ship = resolveShip(server, pos);
        if (ship != null) {
            ship.simulation().setDrainPump(be.enabled, be.rate);
            // 只抽水泵自己所在的舱：物理船的方块实体活在船坞里，要换算成装配坐标。
            ship.simulation().setDrainPumpCell(assemblyPos(server, pos));
        }
    }

    /** 物理船的方块实体在船坞坐标里；转成船状态用的装配坐标。 */
    private static BlockPos assemblyPos(ServerLevel level, BlockPos pos) {
        if (!VsState.active()) {
            return pos;
        }
        return com.opamb.barotrauma.ship.vs.VsPilot.toAssembly(pos);
    }

    @Nullable
    private static ShipState resolveShip(ServerLevel level, BlockPos pos) {
        if (VsState.active()) {
            return VsState.activeState();
        }
        ShipCoreBlockEntity core = ShipCores.nearestCore(level, pos, 32.0D);
        return core == null ? null : core.state();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putBoolean("PumpEnabled", enabled);
        tag.putInt("PumpRate", rate);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        enabled = !tag.contains("PumpEnabled") || tag.getBoolean("PumpEnabled");
        if (tag.contains("PumpRate")) {
            rate = tag.getInt("PumpRate");
        }
    }
}
