package com.opamb.barotrauma.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.jetbrains.annotations.Nullable;

/**
 * 船体格的"可见面"选取（issue #14）。
 *
 * <p>物理船的方块存储/渲染在船坞坐标（= 水面/耐久包里的<strong>船局部坐标</strong>），
 * 方块船的包坐标就是世界坐标——两种形态下都可以用同一份
 * {@code level.getBlockState(格子相对方向的邻居)} 判断某个面是否被实心邻居盖住。
 * 船坞区块没加载时邻居读到空气，自然退化为"六面全可见"，不炸。</p>
 *
 * <p>为什么需要它：受损方块的裂痕贴面和水花粒子如果落在方块中心/被盖住的一面，
 * 会被相邻实心船壳或仍然渲染的 0% 破口方块挡住（水面/耐久包的数据是正常的，
 * 纯表现问题）。粒子必须贴在"观察者能看见的实体面"上。</p>
 */
final class ShipCellFaces {

    private ShipCellFaces() {
    }

    /**
     * 在格子的六个面里挑一个"未被实心邻居盖住、且最朝向相机"的面。
     *
     * @param toWorld 船局部坐标 → 世界坐标（方块船传单位矩阵）
     * @return 船局部坐标系的面的法线；六面全被实心盖住时返回 null
     */
    @Nullable
    static Direction pickVisibleFace(ClientLevel level, BlockPos cell,
                                     Matrix4d toWorld, Vec3 cameraPos) {
        Direction best = null;
        double bestScore = -Double.MAX_VALUE;
        for (Direction dir : Direction.values()) {
            if (isFaceCovered(level, cell, dir)) {
                continue;
            }
            Vector3d faceCenter = toWorld.transformPosition(new Vector3d(
                    cell.getX() + 0.5D + dir.getStepX() * 0.5D,
                    cell.getY() + 0.5D + dir.getStepY() * 0.5D,
                    cell.getZ() + 0.5D + dir.getStepZ() * 0.5D), new Vector3d());
            // 面法线在船局部系里；姿态锁死（永远平行海平面），局部≈世界方向，
            // 用变换后的面中心到相机的向量在局部法线上投影评分即可。
            double score = (cameraPos.x - faceCenter.x) * dir.getStepX()
                    + (cameraPos.y - faceCenter.y) * dir.getStepY()
                    + (cameraPos.z - faceCenter.z) * dir.getStepZ();
            if (score > bestScore) {
                bestScore = score;
                best = dir;
            }
        }
        return best;
    }

    /** 这个面是否被实心邻居盖住（裂痕贴面用：被盖住的画共面，露出的外扩防 z-fighting）。 */
    static boolean isFaceCovered(ClientLevel level, BlockPos cell, Direction dir) {
        BlockPos neighbor = cell.relative(dir);
        return level.getBlockState(neighbor).isSolidRender(level, neighbor);
    }
}
