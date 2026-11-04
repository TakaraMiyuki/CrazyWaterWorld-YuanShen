package com.opamb.barotrauma.block;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 舱门/舱口的形状工具：把"模型局部坐标"换算成方块坐标。
 *
 * <p>GeckoLib 的 {@code GeoBlockRenderer.rotateBlock} 按 FACING 旋转模型
 * （north 0°、south 180°、west 90°、east 270°），所以碰撞/射线形状必须跟着
 * 同一个朝向转，否则东/西向的门会和模型错开。</p>
 *
 * <p>参数单位是像素，以格子中心为 0：{@code along} 是模型 X（沿墙），
 * {@code across} 是模型 Z（垂直墙），{@code y} 从方块底算起（0..16）。</p>
 */
final class DoorShapes {

    private DoorShapes() {
    }

    static VoxelShape slab(Direction facing, double along0, double along1,
                           double across0, double across1, double y0, double y1) {
        double a0 = 0.5D + along0 / 16.0D;
        double a1 = 0.5D + along1 / 16.0D;
        double c0 = 0.5D + across0 / 16.0D;
        double c1 = 0.5D + across1 / 16.0D;
        double yb0 = y0 / 16.0D;
        double yb1 = y1 / 16.0D;
        return switch (facing) {
            case SOUTH -> Shapes.box(1.0D - a1, yb0, 1.0D - c1, 1.0D - a0, yb1, 1.0D - c0);
            case WEST -> Shapes.box(c0, yb0, 1.0D - a1, c1, yb1, 1.0D - a0);
            case EAST -> Shapes.box(1.0D - c1, yb0, a0, 1.0D - c0, yb1, a1);
            default -> Shapes.box(a0, yb0, c0, a1, yb1, c1);
        };
    }
}
