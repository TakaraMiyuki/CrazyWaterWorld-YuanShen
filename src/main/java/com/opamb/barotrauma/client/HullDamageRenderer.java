package com.opamb.barotrauma.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.network.ShipHullDamagePacket;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 受损船壳的客户端表现：原版裂痕 + 水滴 + 方块上的耐久条。
 *
 * <p>《潜渊症》用红→绿矩形描边显示结构剩余耐久；Minecraft 里方块没有“描边”
 * 语义，所以这里在受损方块朝相机的一侧画一条小红绿条。**只保留这一条**——
 * 不再画准星/动作栏的重复条。</p>
 *
 * <p>伤害等级用原版 {@code destroy_stage_*} 裂痕叠在方块上；0% 的 breached
 * 方块仍然正常渲染（只是没碰撞），再叠 stage 9 裂痕，所以不会整面墙消失导致透视。
 * 10%/70% 伤害开始按泄漏强度在方块表面喷水花。</p>
 *
 * <p>所有几何都用 Minecraft 的共享 {@code renderBuffers().bufferSource()}，
 * **不要每帧 new BufferBuilder**：BufferBuilder 的 native 内存回收不及时，
 * 破口多/运行久会 OOM（用户实测 384 KB 分配失败）。</p>
 *
 * <p>物理船的坐标和 {@link ShipWaterRenderer} 完全同源：服务端发装配原坐标 +
 * 装配锚点，客户端用 VS2 的 render 变换映射到世界；方块船直接是世界坐标。
 * 血条/裂痕的“朝相机面”在**船的局部坐标**里算，船旋转后才不会算进方块内部。</p>
 */
@Mod.EventBusSubscriber(modid = BarotraumaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class HullDamageRenderer {

    private static final double MAX_DISTANCE = 40.0D;
    private static final int MAX_BARS = 128;
    /** 原版破坏阶段纹理在方块图集里。 */
    private static final RenderType CRACK_TYPE = RenderType.crumbling(TextureAtlas.LOCATION_BLOCKS);
    private static final TextureAtlasSprite[] CRACK_SPRITES = new TextureAtlasSprite[10];

    private static volatile ShipHullDamagePacket data = ShipHullDamagePacket.empty();

    /** 临时诊断：已经记过日志的方块（packed pos），避免每帧刷屏。 */
    private static final java.util.Set<Long> LOGGED_BARS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private HullDamageRenderer() {
    }

    public static void accept(ShipHullDamagePacket packet) {
        data = packet;
    }

    public static ShipHullDamagePacket data() {
        return data;
    }

    /** 客户端解析后的一个受损格：方块坐标 + 世界中心 + 剩余比例 + 泄漏强度。 */
    public record Target(BlockPos pos, Vec3 center, float health, float leak, double distance) {
    }

    public static List<Target> targets(ClientLevel level, Vec3 cameraPos) {
        Matrix4d toWorld = resolveToWorld(level, data);
        return toWorld == null ? List.of() : targets(cameraPos, toWorld);
    }

    /** 把当前包里的格子映射到世界坐标；物理船找不到 render 船时返回 null。 */
    private static Matrix4d resolveToWorld(ClientLevel level, ShipHullDamagePacket snapshot) {
        Matrix4d toWorld = new Matrix4d();
        if (!snapshot.physical()) {
            return toWorld;
        }
        if (snapshot.shipId() < 0L) {
            return null;
        }
        ClientShip ship = ShipWaterRenderer.findClientShip(level, snapshot.shipId());
        if (ship == null) {
            return null;
        }
        ShipTransform transform = ship.getRenderTransform();
        toWorld.set(transform.getShipToWorld());
        Vector3dc positionInShip = transform.getPositionInShip();
        toWorld.translate(positionInShip.x() - snapshot.anchorX(),
                positionInShip.y() - snapshot.anchorY(),
                positionInShip.z() - snapshot.anchorZ());
        return toWorld;
    }

    private static List<Target> targets(Vec3 cameraPos, Matrix4d toWorld) {
        ShipHullDamagePacket snapshot = data;
        int count = snapshot.count();
        if (count <= 0) {
            return List.of();
        }
        List<Target> out = new ArrayList<>(count);
        float[] entries = snapshot.entries();
        for (int i = 0; i + 4 < entries.length; i += 5) {
            double x = entries[i];
            double y = entries[i + 1];
            double z = entries[i + 2];
            float health = entries[i + 3];
            // NaN 会让血条三个区间比较全落空，画成黑框黑槽；直接不画。
            if (!Float.isFinite(health)) {
                continue;
            }
            Vector3d point = new Vector3d(x + 0.5D, y + 0.5D, z + 0.5D);
            toWorld.transformPosition(point);
            Vec3 center = new Vec3(point.x, point.y, point.z);
            double distance = center.distanceTo(cameraPos);
            if (distance > MAX_DISTANCE) {
                continue;
            }
            out.add(new Target(new BlockPos((int) x, (int) y, (int) z), center,
                    health, entries[i + 4], distance));
        }
        return out;
    }

    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }
        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        Matrix4d toWorld = resolveToWorld(level, data);
        if (toWorld == null) {
            return;
        }
        List<Target> targets = targets(cameraPos, toWorld);
        if (targets.isEmpty()) {
            return;
        }
        if (targets.size() > MAX_BARS) {
            targets.sort(Comparator.comparingDouble(Target::distance));
            targets = targets.subList(0, MAX_BARS);
        }
        PoseStack pose = event.getPoseStack();
        Matrix4f matrix = pose.last().pose();

        drawCracks(mc, level, matrix, targets, toWorld, cameraPos);
        drawBars(mc, camera, matrix, targets, cameraPos);
        spawnLeakParticles(level, targets, cameraPos, toWorld);
    }

    // ------------------------------------------------------------------ 裂痕

    /**
     * 裂痕/血条各自复用一个 builder（同 vanilla {@code renderBuffers()} 的用法：每帧
     * getBuffer→endBatch）。之前每帧 {@code new BufferBuilder} 的 direct 内存要等 GC
     * 才释放，长时间游玩必然 native OOM（实测 8 分钟 {@code Failed to allocate ... bytes}）。
     */
    private static final BufferBuilder CRACK_BUILDER = new BufferBuilder(65536);
    private static final MultiBufferSource.BufferSource CRACK_BUFFERS =
            MultiBufferSource.immediate(CRACK_BUILDER);
    private static final BufferBuilder BAR_BUILDER = new BufferBuilder(8192);
    private static final MultiBufferSource.BufferSource BAR_BUFFERS =
            MultiBufferSource.immediate(BAR_BUILDER);

    private static void drawCracks(Minecraft mc, ClientLevel level, Matrix4f matrix, List<Target> targets,
                                   Matrix4d toWorld, Vec3 cameraPos) {
        VertexConsumer vertices = CRACK_BUFFERS.getBuffer(CRACK_TYPE);
        for (Target target : targets) {
            float health = Mth.clamp(target.health(), 0.0F, 1.0F);
            if (health >= 0.9999F) {
                continue;
            }
            int stage = Mth.clamp((int) Math.ceil((1.0F - health) * 9.0F), 1, 9);
            drawCrackCube(level, vertices, matrix, target.pos(), toWorld, cameraPos, crackSprite(stage));
        }
        // 必须无条件 endBatch（空批次会被 discard）：复用的 builder 若留在 begun 状态，
        // 下一帧 getBuffer 会继续往里累积，顶点坐标跨帧错乱。
        CRACK_BUFFERS.endBatch(CRACK_TYPE);
    }

    private static TextureAtlasSprite crackSprite(int stage) {
        TextureAtlasSprite sprite = CRACK_SPRITES[stage];
        if (sprite == null) {
            sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                    .apply(new ResourceLocation("minecraft", "block/destroy_stage_" + stage));
            CRACK_SPRITES[stage] = sprite;
        }
        return sprite;
    }

    /**
     * 六个面都画，但逐面区分"露出/被盖住"（issue #14）：
     *
     * <p>统一外扩 0.003 的旧写法有两个问题：①顶面绕序反了（法线朝内，被
     * crumbling 的背面剔除剔掉——舱内永远看不到舱顶裂痕）；②被相邻实心船壳
     * 盖住的面，贴面外扩 0.003 会伸进邻居方块体积里，被邻居的表面深度测试
     * 挡死——舱壁贴着舱壁的那些面永远看不到裂痕。</p>
     *
     * <p>现在的规则：露出的面外扩 0.003（避开与自己方块的 z-fighting）；
     * 被盖住的面画<strong>共面</strong>（和邻居表面等深，LEQUAL 深度测试通过、
     * 本批次在地形之后绘制，所以正常叠显——与原版破坏叠加层同机制）。
     * 绕序按叉积逐面验证过（从方块外看逆时针），不再手写六组坐标。</p>
     */
    private static void drawCrackCube(ClientLevel level, VertexConsumer vertices, Matrix4f matrix,
                                      BlockPos pos, Matrix4d toWorld, Vec3 cameraPos,
                                      TextureAtlasSprite sprite) {
        for (Direction dir : Direction.values()) {
            boolean covered = ShipCellFaces.isFaceCovered(level, pos, dir);
            double inflate = covered ? 0.0D : 0.003D;
            // 面所在平面的坐标：露出时向方块外抬 0.003，被盖住时贴齐方块边界
            double fx = pos.getX() + 0.5D + dir.getStepX() * (0.5D + inflate);
            double fy = pos.getY() + 0.5D + dir.getStepY() * (0.5D + inflate);
            double fz = pos.getZ() + 0.5D + dir.getStepZ() * (0.5D + inflate);
            double minX = pos.getX();
            double minY = pos.getY();
            double minZ = pos.getZ();
            double maxX = pos.getX() + 1.0D;
            double maxY = pos.getY() + 1.0D;
            double maxZ = pos.getZ() + 1.0D;
            for (Direction.Axis axis : Direction.Axis.values()) {
                if (dir.getAxis() == axis) {
                    continue;
                }
                // 侧向收 0.001，避免裂痕边角戳进垂直方向的相邻方块
                double shrink = 0.001D;
                switch (axis) {
                    case X -> {
                        minX = minX + shrink;
                        maxX = maxX - shrink;
                    }
                    case Y -> {
                        minY = minY + shrink;
                        maxY = maxY - shrink;
                    }
                    case Z -> {
                        minZ = minZ + shrink;
                        maxZ = maxZ - shrink;
                    }
                    default -> {
                    }
                }
            }
            for (int[] corner : FACE_CORNERS.get(dir.ordinal())) {
                double cx = axisValue(dir, corner[0], minX, fx, maxX);
                double cy = axisValue(dir, corner[1], minY, fy, maxY);
                double cz = axisValue(dir, corner[2], minZ, fz, maxZ);
                float u = corner[3] == 0 ? sprite.getU0() : sprite.getU1();
                float v = corner[4] == 0 ? sprite.getV0() : sprite.getV1();
                crackVertex(vertices, matrix, toWorld, cameraPos, cx, cy, cz, u, v);
            }
        }
    }

    /**
     * 六个面的四角（从方块外看逆时针）+ 各角 UV。
     * 每角 = {X 取值索引, Y 取值索引, Z 取值索引, u, v}；索引 0=min 侧、1=max 侧、2=面平面。
     * 全部用叉积 (v1-v0)×(v2-v0) 验证过法线朝外。
     */
    private static final Map<Integer, int[][]> FACE_CORNERS = buildFaceCorners();

    private static Map<Integer, int[][]> buildFaceCorners() {
        Map<Integer, int[][]> out = new HashMap<>();
        // -Z（北）：从 -Z 看逆时针
        out.put(Direction.NORTH.ordinal(), new int[][]{
                {1, 0, 2, 0, 1}, {0, 0, 2, 1, 1}, {0, 1, 2, 1, 0}, {1, 1, 2, 0, 0}});
        // +Z（南）
        out.put(Direction.SOUTH.ordinal(), new int[][]{
                {0, 0, 2, 0, 1}, {1, 0, 2, 1, 1}, {1, 1, 2, 1, 0}, {0, 1, 2, 0, 0}});
        // -X（西）
        out.put(Direction.WEST.ordinal(), new int[][]{
                {2, 0, 0, 0, 1}, {2, 0, 1, 1, 1}, {2, 1, 1, 1, 0}, {2, 1, 0, 0, 0}});
        // +X（东）
        out.put(Direction.EAST.ordinal(), new int[][]{
                {2, 0, 1, 0, 1}, {2, 0, 0, 1, 1}, {2, 1, 0, 1, 0}, {2, 1, 1, 0, 0}});
        // -Y（底）
        out.put(Direction.DOWN.ordinal(), new int[][]{
                {0, 2, 0, 0, 1}, {1, 2, 0, 1, 1}, {1, 2, 1, 1, 0}, {0, 2, 1, 0, 0}});
        // +Y（顶）
        out.put(Direction.UP.ordinal(), new int[][]{
                {0, 2, 1, 0, 1}, {1, 2, 1, 1, 1}, {1, 2, 0, 1, 0}, {0, 2, 0, 0, 0}});
        return out;
    }

    /** 按角索引取坐标：0=min 侧、1=max 侧、2=面平面。 */
    private static double axisValue(Direction faceDir, int index, double min, double face, double max) {
        return switch (index) {
            case 0 -> min;
            case 1 -> max;
            default -> face;
        };
    }

    private static void crackVertex(VertexConsumer vertices, Matrix4f matrix, Matrix4d toWorld,
                                    Vec3 cameraPos, double x, double y, double z, float u, float v) {
        Vector3d world = toWorld.transformPosition(new Vector3d(x, y, z), new Vector3d());
        vertices.vertex(matrix,
                        (float) (world.x - cameraPos.x),
                        (float) (world.y - cameraPos.y),
                        (float) (world.z - cameraPos.z))
                .color(1.0F, 1.0F, 1.0F, 1.0F)
                .uv(u, v)
                .uv2(0x00F000F0)
                // 1.20.1 的 crumbling 格式是 Position + Color + UV0 + UV2 + Normal + Padding；
                // 少写 normal 会直接 "Not filled all elements of the vertex" 崩客户端。
                .normal(0.0F, 1.0F, 0.0F)
                .endVertex();
    }

    // ------------------------------------------------------------------ 血条

    /**
     * 大血条（回到初版方案）：始终正对相机的公告牌，按距离放大。
     *
     * <p>贴面 decal 有一个躲不开的坑：选"朝相机的面"要用船的局部坐标，而船沉了大截之后，
     * 相机在船局部坐标里高出船壳几十格——按局部坐标选面永远选中方块顶面，从侧面看
     * 顶面上的条就是一条压扁的黑缝（用户实测"血条黑"，日志里 health 全部正常）。
     * 公告牌永远正对相机，不存在选面问题。</p>
     *
     * <p>初版浮动条的"嵌墙变暗"也修掉了：公告牌沿主导轴往外抬，抬升量 =
     * 0.12 + 半宽 × |视线与法线夹角余弦|，正好保证贴图面的边无论什么角度都悬在
     * 舱壁外侧，不会切进相邻方块被深度测试切成暗色残条。</p>
     */
    private static void drawBars(Minecraft mc, Camera camera, Matrix4f matrix, List<Target> targets,
                                 Vec3 cameraPos) {
        VertexConsumer vertices = BAR_BUFFERS.getBuffer(BarRenderType.TYPE);
        Vec3 right = new Vec3(camera.getLeftVector()).scale(-1.0D);
        Vec3 up = new Vec3(camera.getUpVector());
        for (Target target : targets) {
            if (target.health() >= 0.9999F) {
                continue;
            }
            // 排查血条用的临时日志：每格只记一次，能对上玩家报的位置和原始数值。
            if (LOGGED_BARS.add(target.pos().asLong())) {
                BarotraumaMod.LOGGER.info("[hull-bar] pos={} health={} bits={} leak={} ship={}",
                        target.pos().toShortString(), target.health(),
                        Float.floatToIntBits(target.health()), target.leak(), data.shipId());
            }
            Vec3 center = target.center();
            double dx = cameraPos.x - center.x;
            double dy = cameraPos.y - center.y;
            double dz = cameraPos.z - center.z;
            double ax = Math.abs(dx);
            double ay = Math.abs(dy);
            double az = Math.abs(dz);
            double nx = 0.0D;
            double ny = 0.0D;
            double nz = 0.0D;
            if (ax >= ay && ax >= az) {
                nx = dx >= 0.0D ? 1.0D : -1.0D;
            } else if (ay >= az) {
                ny = dy >= 0.0D ? 1.0D : -1.0D;
            } else {
                nz = dz >= 0.0D ? 1.0D : -1.0D;
            }
            float health = Mth.clamp(target.health(), 0.0F, 1.0F);
            double slotWidth = Math.min(1.4D, 0.55D + target.distance() * 0.04D);
            double slotHeight = slotWidth * 0.16D;
            double hw = slotWidth * 0.5D;
            double hh = slotHeight * 0.5D;
            // 公告牌沿主导轴外抬：抬升量减掉牌子在法线方向上的半宽后还剩 0.1——
            // 无论视角多斜，牌子靠墙的那条边都悬在方块面外侧 0.1，不会再切进舱壁
            // 被深度测试剪成一条红丝（用户实测"血条卡墙里"：之前的公式视角一斜就切墙）。
            double edge = hw * Math.abs(right.x * nx + right.y * ny + right.z * nz)
                    + hh * Math.abs(up.x * nx + up.y * ny + up.z * nz);
            double offset = 0.60D + edge;
            double bx = center.x + nx * offset;
            double by = center.y + ny * offset;
            double bz = center.z + nz * offset;
            billboardQuad(vertices, matrix, cameraPos, bx, by, bz, right, up,
                    slotWidth * 1.15D, slotHeight * 1.5D, 0.0F, 0.0F, 0.0F, 0.78F);
            if (health <= 0.001F) {
                // 0%（破口）：整条亮红，水下/暗处也一眼能认出来。
                billboardQuad(vertices, matrix, cameraPos, bx, by, bz, right, up,
                        slotWidth, slotHeight, 0.95F, 0.12F, 0.10F, 1.0F);
            } else {
                billboardQuad(vertices, matrix, cameraPos, bx, by, bz, right, up,
                        slotWidth, slotHeight, 0.07F, 0.07F, 0.07F, 0.90F);
            }
            if (health > 0.001F) {
                int rgb = Mth.hsvToRgb(health * 0.33F, 0.85F, 1.0F);
                float red = ((rgb >> 16) & 0xFF) / 255.0F;
                float green = ((rgb >> 8) & 0xFF) / 255.0F;
                float blue = (rgb & 0xFF) / 255.0F;
                // 刚掉一点血时填充宽度小到看不见——给一个最小可见宽度，靠左对齐。
                double fillWidth = Math.max(slotWidth * health, slotWidth * 0.12D);
                double shift = (slotWidth - fillWidth) * 0.5D;
                billboardQuad(vertices, matrix, cameraPos,
                        bx - right.x * shift, by - right.y * shift, bz - right.z * shift,
                        right, up, fillWidth, slotHeight * 0.6D, red, green, blue, 1.0F);
            }
        }
        BAR_BUFFERS.endBatch(BarRenderType.TYPE);
    }

    /** 相机坐标系里的一个实心四边形（公告牌），顶点在世界坐标给出。 */
    private static void billboardQuad(VertexConsumer vertices, Matrix4f matrix, Vec3 cameraPos,
                                      double cx, double cy, double cz,
                                      Vec3 right, Vec3 up,
                                      double width, double height,
                                      float r, float g, float b, float a) {
        double hw = width * 0.5D;
        double hh = height * 0.5D;
        billboardVertex(vertices, matrix, cameraPos,
                cx - right.x * hw - up.x * hh, cy - right.y * hw - up.y * hh,
                cz - right.z * hw - up.z * hh, r, g, b, a);
        billboardVertex(vertices, matrix, cameraPos,
                cx + right.x * hw - up.x * hh, cy + right.y * hw - up.y * hh,
                cz + right.z * hw - up.z * hh, r, g, b, a);
        billboardVertex(vertices, matrix, cameraPos,
                cx + right.x * hw + up.x * hh, cy + right.y * hw + up.y * hh,
                cz + right.z * hw + up.z * hh, r, g, b, a);
        billboardVertex(vertices, matrix, cameraPos,
                cx - right.x * hw + up.x * hh, cy - right.y * hw + up.y * hh,
                cz - right.z * hw + up.z * hh, r, g, b, a);
    }

    private static void billboardVertex(VertexConsumer vertices, Matrix4f matrix, Vec3 cameraPos,
                                        double x, double y, double z,
                                        float r, float g, float b, float a) {
        vertices.vertex(matrix,
                        (float) (x - cameraPos.x),
                        (float) (y - cameraPos.y),
                        (float) (z - cameraPos.z))
                .color(r, g, b, a)
                .endVertex();
    }

    // ------------------------------------------------------------------ 水花

    private static void spawnLeakParticles(ClientLevel level, List<Target> targets, Vec3 cameraPos,
                                           Matrix4d toWorld) {
        // 5 Hz 检查，实际每个泄漏点每 0.5 秒喷 1~4 个水花。
        if (level.getGameTime() % 10L != 0L) {
            return;
        }
        for (Target target : targets) {
            if (target.leak() <= 0.0F) {
                continue;
            }
            // 贴"未被实心邻居盖住、且最朝向相机"的面（issue #14）：旧的相机侧 AABB
            // 放置会把粒子放进相邻实心方块的体积里，某些朝向整片被挡。
            Vec3 at = null;
            Direction face = ShipCellFaces.pickVisibleFace(level, target.pos(), toWorld, cameraPos);
            if (face != null) {
                Vector3d faceCenter = toWorld.transformPosition(new Vector3d(
                        target.pos().getX() + 0.5D + face.getStepX() * 0.5D,
                        target.pos().getY() + 0.5D + face.getStepY() * 0.5D,
                        target.pos().getZ() + 0.5D + face.getStepZ() * 0.5D), new Vector3d());
                at = new Vec3(faceCenter.x + face.getStepX() * 0.06D,
                        faceCenter.y + face.getStepY() * 0.06D,
                        faceCenter.z + face.getStepZ() * 0.06D);
            } else {
                // 六面全被盖住（嵌墙格）：退回旧的相机侧放置，聊胜于无
                Vec3 towardCamera = cameraPos.subtract(target.center());
                if (towardCamera.lengthSqr() < 1.0E-6D) {
                    continue;
                }
                Vec3 toward = towardCamera.normalize();
                double faceDist = 0.5D / Math.max(Math.abs(toward.x),
                        Math.max(Math.abs(toward.y), Math.abs(toward.z)));
                at = target.center().add(toward.scale(faceDist + 0.08D));
            }
            int count = target.leak() >= 0.9F ? 4 : 1;
            for (int i = 0; i < count; i++) {
                double ox = (level.random.nextDouble() - 0.5D) * 0.30D;
                double oy = (level.random.nextDouble() - 0.5D) * 0.30D;
                double oz = (level.random.nextDouble() - 0.5D) * 0.30D;
                level.addParticle(ParticleTypes.SPLASH,
                        at.x + ox, at.y + oy, at.z + oz,
                        (level.random.nextDouble() - 0.5D) * 0.05D,
                        -0.05D,
                        (level.random.nextDouble() - 0.5D) * 0.05D);
            }
        }
    }

    /**
     * 不写深度的半透明条 RenderType，和水面同一套参数；这样船内水/粒子不会把条
     * 整片盖掉，但条仍然会被真正在它前面的方块挡住。
     */
    private static final class BarRenderType extends RenderStateShard {

        private BarRenderType() {
            super("barotrauma_hull_bar", () -> {
            }, () -> {
            });
        }

        private static final RenderType TYPE = RenderType.create(
                "barotrauma_hull_bar",
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS,
                256,
                false,
                true,
                RenderType.CompositeState.builder()
                        .setShaderState(new ShaderStateShard(GameRenderer::getPositionColorShader))
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setWriteMaskState(new WriteMaskStateShard(true, false))
                        .createCompositeState(false));
    }
}
