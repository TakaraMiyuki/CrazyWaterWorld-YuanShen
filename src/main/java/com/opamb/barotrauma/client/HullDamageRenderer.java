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
 * <p>物理船的坐标和 {@link ShipWaterRenderer} 完全同源：服务端发装配原坐标 +
 * 装配锚点，客户端用 VS2 的 render 变换映射到世界；方块船直接是世界坐标。</p>
 */
@Mod.EventBusSubscriber(modid = BarotraumaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class HullDamageRenderer {

    private static final double MAX_DISTANCE = 40.0D;
    private static final int MAX_BARS = 128;
    /** 原版破坏阶段纹理在方块图集里。 */
    private static final RenderType CRACK_TYPE = RenderType.crumbling(TextureAtlas.LOCATION_BLOCKS);
    private static final TextureAtlasSprite[] CRACK_SPRITES = new TextureAtlasSprite[10];

    private static volatile ShipHullDamagePacket data = ShipHullDamagePacket.empty();

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
            Vector3d point = new Vector3d(x + 0.5D, y + 0.5D, z + 0.5D);
            toWorld.transformPosition(point);
            Vec3 center = new Vec3(point.x, point.y, point.z);
            double distance = center.distanceTo(cameraPos);
            if (distance > MAX_DISTANCE) {
                continue;
            }
            out.add(new Target(new BlockPos((int) x, (int) y, (int) z), center,
                    entries[i + 3], entries[i + 4], distance));
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

        drawCracks(matrix, targets, toWorld, cameraPos);
        drawBars(camera, matrix, targets, cameraPos);
        spawnLeakParticles(level, targets, cameraPos);
    }

    // ------------------------------------------------------------------ 裂痕

    private static void drawCracks(Matrix4f matrix, List<Target> targets,
                                   Matrix4d toWorld, Vec3 cameraPos) {
        BufferBuilder builder = new BufferBuilder(65536);
        MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(builder);
        VertexConsumer vertices = buffers.getBuffer(CRACK_TYPE);
        boolean any = false;
        for (Target target : targets) {
            float health = Mth.clamp(target.health(), 0.0F, 1.0F);
            if (health >= 0.9999F) {
                continue;
            }
            int stage = Mth.clamp((int) Math.ceil((1.0F - health) * 9.0F), 1, 9);
            drawCrackCube(vertices, matrix, target.pos(), toWorld, cameraPos, crackSprite(stage));
            any = true;
        }
        if (any) {
            buffers.endBatch(CRACK_TYPE);
        }
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

    private static void drawCrackCube(VertexConsumer vertices, Matrix4f matrix, BlockPos pos,
                                      Matrix4d toWorld, Vec3 cameraPos, TextureAtlasSprite sprite) {
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        // -Z
        crackFace(vertices, matrix, toWorld, cameraPos, pos, u0, u1, v0, v1,
                0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0);
        // +Z
        crackFace(vertices, matrix, toWorld, cameraPos, pos, u0, u1, v0, v1,
                1, 0, 1, 0, 0, 1, 0, 1, 1, 1, 1, 1);
        // -X
        crackFace(vertices, matrix, toWorld, cameraPos, pos, u0, u1, v0, v1,
                0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 1, 1);
        // +X
        crackFace(vertices, matrix, toWorld, cameraPos, pos, u0, u1, v0, v1,
                1, 0, 0, 1, 0, 1, 1, 1, 1, 1, 1, 0);
        // -Y
        crackFace(vertices, matrix, toWorld, cameraPos, pos, u0, u1, v0, v1,
                0, 0, 0, 0, 0, 1, 1, 0, 1, 1, 0, 0);
        // +Y
        crackFace(vertices, matrix, toWorld, cameraPos, pos, u0, u1, v0, v1,
                0, 1, 1, 1, 1, 1, 1, 1, 0, 0, 1, 0);
    }

    private static void crackFace(VertexConsumer vertices, Matrix4f matrix, Matrix4d toWorld,
                                  Vec3 cameraPos, BlockPos pos, float u0, float u1, float v0, float v1,
                                  double x0, double y0, double z0,
                                  double x1, double y1, double z1,
                                  double x2, double y2, double z2,
                                  double x3, double y3, double z3) {
        crackVertex(vertices, matrix, toWorld, cameraPos, pos, x0, y0, z0, u0, v1);
        crackVertex(vertices, matrix, toWorld, cameraPos, pos, x1, y1, z1, u1, v1);
        crackVertex(vertices, matrix, toWorld, cameraPos, pos, x2, y2, z2, u1, v0);
        crackVertex(vertices, matrix, toWorld, cameraPos, pos, x3, y3, z3, u0, v0);
    }

    private static void crackVertex(VertexConsumer vertices, Matrix4f matrix, Matrix4d toWorld,
                                    Vec3 cameraPos, BlockPos pos, double dx, double dy, double dz,
                                    float u, float v) {
        double inflate = 0.002D;
        Vector3d world = toWorld.transformPosition(new Vector3d(
                pos.getX() + dx * (1.0D + inflate * 2.0D) - inflate,
                pos.getY() + dy * (1.0D + inflate * 2.0D) - inflate,
                pos.getZ() + dz * (1.0D + inflate * 2.0D) - inflate), new Vector3d());
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

    private static void drawBars(Camera camera, Matrix4f matrix, List<Target> targets, Vec3 cameraPos) {
        Vec3 right = new Vec3(camera.getLeftVector()).scale(-1.0D);
        Vec3 up = new Vec3(camera.getUpVector());
        BufferBuilder builder = new BufferBuilder(8192);
        MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(builder);
        VertexConsumer vertices = buffers.getBuffer(BarRenderType.TYPE);
        for (Target target : targets) {
            if (target.health() >= 0.9999F) {
                continue;
            }
            Vec3 towardCamera = cameraPos.subtract(target.center());
            if (towardCamera.lengthSqr() < 1.0E-6D) {
                continue;
            }
            // 贴在方块朝相机的一面上；条收窄到 0.72，避免转角处被相邻墙切掉一半。
            Vec3 toward = towardCamera.normalize();
            double face = 0.5D / Math.max(Math.abs(toward.x),
                    Math.max(Math.abs(toward.y), Math.abs(toward.z)));
            // 稍微离墙一点 + 条收窄：转角处不会被相邻方块切掉半条。
            Vec3 anchor = target.center().add(toward.scale(face + 0.10D));
            Vec3 relative = anchor.subtract(cameraPos);
            float health = Mth.clamp(target.health(), 0.0F, 1.0F);
            quad(vertices, matrix, relative, right, up, 0.62D, 0.16D,
                    0.0F, 0.0F, 0.0F, 0.78F);
            if (health <= 0.001F) {
                quad(vertices, matrix, relative, right, up, 0.56D, 0.12D,
                        0.30F, 0.03F, 0.03F, 0.90F);
            } else {
                quad(vertices, matrix, relative, right, up, 0.56D, 0.12D,
                        0.07F, 0.07F, 0.07F, 0.90F);
            }
            int rgb = Mth.hsvToRgb(health * 0.33F, 0.85F, 1.0F);
            float red = ((rgb >> 16) & 0xFF) / 255.0F;
            float green = ((rgb >> 8) & 0xFF) / 255.0F;
            float blue = (rgb & 0xFF) / 255.0F;
            quadLeft(vertices, matrix, relative, right, up, 0.52D, 0.08D, health,
                    red, green, blue, 1.0F);
        }
        buffers.endBatch(BarRenderType.TYPE);
    }

    private static void quad(VertexConsumer vertices, Matrix4f matrix, Vec3 center,
                             Vec3 right, Vec3 up, double width, double height,
                             float r, float g, float b, float a) {
        Vec3 left = center.add(right.scale(-width * 0.5D));
        Vec3 bottom = left.add(up.scale(-height * 0.5D));
        Vec3 top = bottom.add(up.scale(height));
        Vec3 rightBottom = bottom.add(right.scale(width));
        Vec3 rightTop = top.add(right.scale(width));
        vertex(vertices, matrix, bottom, r, g, b, a);
        vertex(vertices, matrix, rightBottom, r, g, b, a);
        vertex(vertices, matrix, rightTop, r, g, b, a);
        vertex(vertices, matrix, top, r, g, b, a);
    }

    /** 从左端开始填充的长度条。 */
    private static void quadLeft(VertexConsumer vertices, Matrix4f matrix, Vec3 center,
                                 Vec3 right, Vec3 up, double width, double height, float ratio,
                                 float r, float g, float b, float a) {
        double filled = width * Mth.clamp(ratio, 0.0F, 1.0F);
        Vec3 left = center.add(right.scale(-width * 0.5D));
        Vec3 bottom = left.add(up.scale(-height * 0.5D));
        Vec3 top = bottom.add(up.scale(height));
        Vec3 rightBottom = bottom.add(right.scale(filled));
        Vec3 rightTop = top.add(right.scale(filled));
        vertex(vertices, matrix, bottom, r, g, b, a);
        vertex(vertices, matrix, rightBottom, r, g, b, a);
        vertex(vertices, matrix, rightTop, r, g, b, a);
        vertex(vertices, matrix, top, r, g, b, a);
    }

    private static void vertex(VertexConsumer vertices, Matrix4f matrix, Vec3 pos,
                               float r, float g, float b, float a) {
        vertices.vertex(matrix, (float) pos.x, (float) pos.y, (float) pos.z)
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
