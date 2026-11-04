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
import java.util.List;

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

        drawCracks(mc, matrix, targets, toWorld, cameraPos);
        drawBars(mc, camera, matrix, targets, cameraPos);
        spawnLeakParticles(level, targets, cameraPos);
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

    private static void drawCracks(Minecraft mc, Matrix4f matrix, List<Target> targets,
                                   Matrix4d toWorld, Vec3 cameraPos) {
        VertexConsumer vertices = CRACK_BUFFERS.getBuffer(CRACK_TYPE);
        for (Target target : targets) {
            float health = Mth.clamp(target.health(), 0.0F, 1.0F);
            if (health >= 0.9999F) {
                continue;
            }
            int stage = Mth.clamp((int) Math.ceil((1.0F - health) * 9.0F), 1, 9);
            drawCrackCube(vertices, matrix, target.pos(), toWorld, cameraPos, crackSprite(stage));
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
     * 六个面都画。只画“最近面”在方块被相邻方块挡住/斜看时会完全看不到裂痕；
     * 现在方块模型正常渲染，背面裂痕会被深度测试挡住，六面画不会出现透视，
     * 但能保证任何朝向都能看到损伤。
     */
    private static void drawCrackCube(VertexConsumer vertices, Matrix4f matrix, BlockPos pos,
                                      Matrix4d toWorld, Vec3 cameraPos, TextureAtlasSprite sprite) {
        double inflate = 0.003D;
        double x0 = pos.getX() - inflate;
        double y0 = pos.getY() - inflate;
        double z0 = pos.getZ() - inflate;
        double x1 = pos.getX() + 1.0D + inflate;
        double y1 = pos.getY() + 1.0D + inflate;
        double z1 = pos.getZ() + 1.0D + inflate;
        crackFace(vertices, matrix, toWorld, cameraPos, sprite,
                x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
        crackFace(vertices, matrix, toWorld, cameraPos, sprite,
                x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1);
        crackFace(vertices, matrix, toWorld, cameraPos, sprite,
                x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1);
        crackFace(vertices, matrix, toWorld, cameraPos, sprite,
                x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0);
        crackFace(vertices, matrix, toWorld, cameraPos, sprite,
                x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0);
        crackFace(vertices, matrix, toWorld, cameraPos, sprite,
                x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);
    }

    private static void crackFace(VertexConsumer vertices, Matrix4f matrix, Matrix4d toWorld,
                                  Vec3 cameraPos, TextureAtlasSprite sprite,
                                  double x0, double y0, double z0,
                                  double x1, double y1, double z1,
                                  double x2, double y2, double z2,
                                  double x3, double y3, double z3) {
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        // 必须逆时针（从方块外看）：1.20.1 的 crumbling RenderType 默认开背面剔除，
        // 顺时针的面从外面看整面被剔掉——之前"只有钻进方块内部才看得见裂痕"就是这个原因。
        crackVertex(vertices, matrix, toWorld, cameraPos, x3, y3, z3, u0, v0);
        crackVertex(vertices, matrix, toWorld, cameraPos, x2, y2, z2, u1, v0);
        crackVertex(vertices, matrix, toWorld, cameraPos, x1, y1, z1, u1, v1);
        crackVertex(vertices, matrix, toWorld, cameraPos, x0, y0, z0, u0, v1);
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

    private static void spawnLeakParticles(ClientLevel level, List<Target> targets, Vec3 cameraPos) {
        // 5 Hz 检查，实际每个泄漏点每 0.5 秒喷 1~4 个水花。
        if (level.getGameTime() % 10L != 0L) {
            return;
        }
        for (Target target : targets) {
            if (target.leak() <= 0.0F) {
                continue;
            }
            Vec3 towardCamera = cameraPos.subtract(target.center());
            if (towardCamera.lengthSqr() < 1.0E-6D) {
                continue;
            }
            Vec3 toward = towardCamera.normalize();
            double face = 0.5D / Math.max(Math.abs(toward.x),
                    Math.max(Math.abs(toward.y), Math.abs(toward.z)));
            Vec3 at = target.center().add(toward.scale(face + 0.08D));
            int count = target.leak() >= 0.9F ? 4 : 1;
            for (int i = 0; i < count; i++) {
                double ox = (level.random.nextDouble() - 0.5D) * 0.35D;
                double oy = (level.random.nextDouble() - 0.5D) * 0.35D;
                double oz = (level.random.nextDouble() - 0.5D) * 0.35D;
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
