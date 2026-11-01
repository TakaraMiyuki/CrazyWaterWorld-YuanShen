package com.opamb.barotrauma.ship;

import com.opamb.barotrauma.blockentity.ShipCoreBlockEntity;
import com.opamb.barotrauma.entity.VesselEntity;
import com.opamb.barotrauma.registry.ModEntities;
import com.opamb.barotrauma.registry.ModShipBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 船体装配 / 展开：方块 ↔ 结构体实体。
 */
public final class ShipAssembler {

    public record Result(boolean ok, String message, int blocks) {
    }

    private ShipAssembler() {
    }

    /** 把船收成结构体实体（方块离开世界）。 */
    public static Result launch(ServerLevel level, BlockPos corePos) {
        BlockEntity be = level.getBlockEntity(corePos);
        if (!(be instanceof ShipCoreBlockEntity core)) {
            return new Result(false, "没有船核心", 0);
        }
        ShipState ship = core.rescan(level);
        Set<BlockPos> hull = ship.geometry().hull();
        if (hull.isEmpty()) {
            return new Result(false, "船体为空", 0);
        }
        Set<BlockPos> moving = new HashSet<>(hull);
        moving.add(corePos);

        ShipGeometry.Bounds bounds = ship.geometry().bounds();
        BlockPos origin = new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ());

        List<BlockState> states = new ArrayList<>(moving.size());
        List<BlockPos> offsets = new ArrayList<>(moving.size());
        for (BlockPos pos : moving) {
            states.add(level.getBlockState(pos));
            offsets.add(pos.subtract(origin));
        }
        for (BlockPos pos : moving) {
            level.setBlock(pos, Blocks.WATER.defaultBlockState(), 2);
        }

        VesselEntity vessel = ModEntities.VESSEL.get().create(level);
        if (vessel == null) {
            return new Result(false, "无法创建船实体", 0);
        }
        vessel.moveTo(origin.getX(), origin.getY(), origin.getZ(), 0.0F, 0.0F);
        vessel.setPalette(states, offsets, bounds.sizeX(), bounds.sizeY(), bounds.sizeZ());
        level.addFreshEntity(vessel);
        return new Result(true, "已收成结构体（" + states.size() + " 格）", states.size());
    }

    /** 把结构体实体展开回方块。 */
    public static Result dock(ServerLevel level, VesselEntity vessel) {
        int placed = vessel.deploy();
        BlockPos corePos = null;
        for (int i = 0; i < vessel.palette().size(); i++) {
            if (vessel.palette().get(i).getBlock() == ModShipBlocks.SHIP_CORE.get()) {
                BlockPos offset = vessel.offsets().get(i);
                corePos = BlockPos.containing(vessel.getX() + offset.getX(),
                        vessel.getY() + offset.getY(), vessel.getZ() + offset.getZ());
                break;
            }
        }
        vessel.discard();
        if (corePos != null && level.getBlockEntity(corePos) instanceof ShipCoreBlockEntity core) {
            core.rescan(level);
        }
        return new Result(true, "已展开（" + placed + " 格）", placed);
    }
}
