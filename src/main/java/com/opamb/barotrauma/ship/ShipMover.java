package com.opamb.barotrauma.ship;

import com.opamb.barotrauma.blockentity.ShipCoreBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 船体平移（最小可动版本）。
 *
 * <p>没有物理引擎，所以用"剪切 → 校验 → 粘贴"的方式移动整艘船：先把船体方块读进内存，
 * 校验目标位置没有实心阻挡，再把旧位置填回海水、在新位置重建，并把船范围内的实体一起平移。
 * 这是离散移动；后续要做平滑推进时，会换成"船体结构体 + 每 tick 插值"。</p>
 */
public final class ShipMover {

    public record Result(boolean ok, String message, int blocks) {
    }

    private ShipMover() {
    }

    public static Result move(ServerLevel level, BlockPos corePos, int dx, int dy, int dz) {
        BlockEntity be = level.getBlockEntity(corePos);
        if (!(be instanceof ShipCoreBlockEntity core) || core.state() == null) {
            return new Result(false, "没有船核心或尚未扫描", 0);
        }
        ShipState state = core.state();
        // 用包围盒里的全部船方块，而不是 geometry.hull()（最大连通分量）：
        // 门、玻璃、单独挂着的机器如果不连通，就不会被搬走，留在原地。
        Set<BlockPos> moving = state.collectBlocks(level);
        if (moving.isEmpty()) {
            return new Result(false, "船体为空", 0);
        }
        // 核心可能悬在舱室中间、不与船体相连，必须显式带上
        moving.add(corePos);

        // 1) 目标占位检查（船自己的格子不算阻挡）
        for (BlockPos pos : moving) {
            BlockPos target = pos.offset(dx, dy, dz);
            if (moving.contains(target)) {
                continue;
            }
            BlockState targetState = level.getBlockState(target);
            if (!targetState.isAir() && targetState.getFluidState().isEmpty()) {
                return new Result(false, "目标位置被阻挡：" + target.toShortString(), 0);
            }
        }

        // 2) 记录方块和舱内格子
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (BlockPos pos : moving) {
            blocks.put(pos, level.getBlockState(pos));
        }
        List<BlockPos> interior = new ArrayList<>();
        for (ShipGeometry.Compartment compartment : state.compartments()) {
            interior.addAll(compartment.cells());
        }
        for (ShipGeometry.Compartment pocket : state.geometry().openPockets()) {
            interior.addAll(pocket.cells());
        }

        ShipGeometry.Bounds bounds = state.geometry().bounds();
        boolean underwater = level.getBlockState(new BlockPos(bounds.minX() - 1,
                (bounds.minY() + bounds.maxY()) / 2, (bounds.minZ() + bounds.maxZ()) / 2))
                .getFluidState().is(net.minecraft.tags.FluidTags.WATER);
        BlockState outside = underwater ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();

        // 3) 清空旧船体 → 填回海水
        for (BlockPos pos : moving) {
            level.setBlock(pos, outside, 2);
        }
        // 4) 新位置：先清舱内，再放船体
        for (BlockPos pos : interior) {
            level.setBlock(pos.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
        }
        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            level.setBlock(entry.getKey().offset(dx, dy, dz), entry.getValue(), 3);
        }

        // 5) 把船范围内的实体一起搬走
        AABB box = new AABB(bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX() + 1.0D, bounds.maxY() + 1.0D, bounds.maxZ() + 1.0D);
        for (Entity entity : level.getEntities((Entity) null, box, e -> true)) {
            entity.teleportTo(entity.getX() + dx, entity.getY() + dy, entity.getZ() + dz);
        }

        // 6) 新核心接管平移后的几何（刚体平移，不用重扫；温度和水位跟着船走）
        BlockPos newCore = corePos.offset(dx, dy, dz);
        if (level.getBlockEntity(newCore) instanceof ShipCoreBlockEntity moved) {
            moved.setState(state.shifted(dx, dy, dz));
        } else {
            // 理论上不会发生；真发生了就退回重扫
            for (BlockPos pos : moving) {
                if (level.getBlockEntity(pos.offset(dx, dy, dz)) instanceof ShipCoreBlockEntity scan) {
                    scan.rescan(level);
                }
            }
        }
        return new Result(true, "移动 " + blocks.size() + " 格", blocks.size());
    }
}
