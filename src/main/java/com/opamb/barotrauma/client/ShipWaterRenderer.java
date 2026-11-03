package com.opamb.barotrauma.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.network.ShipWaterStatePacket;
import com.opamb.barotrauma.world.OceanEnvironment;
import com.opamb.barotrauma.world.StarterSub;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;

import java.util.Locale;

/**
 * 船内平滑水面渲染。
 *
 * <p>服务端把每个舱室的包围盒 + 水面高度发过来；客户端找到物理船的实时渲染位置，
 * 按"渲染位置 − 装配锚点"整体平移，画一个半透明水体（顶面 + 四个侧面）。
 * 水位是连续值，所以涨水看起来是平滑上升的，不会有 MC 水的半格跳变。</p>
 *
 * <h2>诊断（默认关闭）</h2>
 *
 * <p>第 0 步先只加诊断、保留原渲染行为（`drawWaterLegacy`）；随后是已由源码证实的独立修复
 * （相机相对坐标 + {@link RenderType#debugQuads()}，默认启用，`-Dbarotrauma.waterFix=false`
 * 可回退）。诊断打开方式（任一）：</p>
 * <ul>
 *   <li>JVM 参数 {@code -Dbarotrauma.waterDebug=true}；</li>
 *   <li>环境变量 {@code BAROTRAUMA_WATER_DEBUG=1}；</li>
 *   <li>游戏内按 <b>F9</b> 切换。</li>
 * </ul>
 *
 * <p>打开后每秒输出一条 {@code [water-diag]}：相机/眼睛/玩家坐标、相机实体、船的
 * render/current/prev 变换、锚点/位移、水面盒、CPU/GPU 模型视图与投影矩阵、顶点数、
 * flush/RenderType/stage；并在视线前方 2m 画三个分离的 0.2m 测试面（不写世界方块）：
 * 每个位置同时用"现有管线"和"诊断显式管线"各画一次，另有深度测试开/关对照，
 * 用来一次启动区分坐标空间、相机/船位置、RenderType 状态与 flush 问题。</p>
 */
@Mod.EventBusSubscriber(modid = BarotraumaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ShipWaterRenderer {

    /** 初始值：系统属性 / 环境变量（默认关闭）。游戏内 F9 可随时切换。 */
    private static final boolean DEBUG_DEFAULT = readDebugDefault();
    /**
     * 已证实修复的独立开关（独立补丁，可随时回退）：
     * {@code -Dbarotrauma.waterFix=false} 退回原来的"绝对世界坐标 + debugFilledBox"。
     *
     * <p>修复依据（本机实际版本源码，不是猜的）：</p>
     * <ul>
     *   <li>Forge 47.4.0 {@code GameRenderer.renderLevel} 只给 level 的 PoseStack 乘相机旋转，
     *       从不 translate(-camera)；{@code LevelRenderer.renderChunkLayer} 把同一个 PoseStack
     *       直接交给事件。VS2 2.4.11 自己渲染船体时显式做
     *       {@code translate(-cam) * shipToWorld * translate(blockPos)}。所以事件里的顶点必须是
     *       相机相对坐标。</li>
     *   <li>{@code RenderType.debugFilledBox()} 的 mode 是 TRIANGLE_STRIP；把多个独立 quad
     *       串在一起会连出错误三角形。{@code RenderType.debugQuads()} 是 QUADS + NO_CULL +
     *       TRANSLUCENT_TRANSPARENCY，默认 depthTest=LEQUAL、writeMask=COLOR_DEPTH_WRITE。</li>
     *   <li>原来的 {@code RenderSystem.disableCull()/depthMask(false)} 在 endBatch 之前调用，
     *       会被 RenderType.setupRenderState 覆盖，实际仍是 CULL + 写深度——从舱内往上看
     *       水面是背面，会被剔除。</li>
     * </ul>
     */
    private static final boolean FIX_MAIN_WATER = !"false".equalsIgnoreCase(
            System.getProperty("barotrauma.waterFix", "true"));

    /** 空气海域方案：不再绘制任何水体，只保留水面包用于“船舱是否干燥”的判断。 */
    private static final boolean RENDER_WATER = false;

    private static volatile boolean debug = DEBUG_DEFAULT;
    private static volatile ShipWaterStatePacket state = ShipWaterStatePacket.empty();
    /** 最近一帧的船体渲染位置相对装配锚点的位移，供 isInsideDryZone 使用。 */
    private static volatile float lastDeltaX;
    private static volatile float lastDeltaY;
    private static volatile float lastDeltaZ;
    private static volatile boolean lastShipFound;
    private static long packetCount;
    // 不要用 Long.MIN_VALUE 做初值：gameTime - Long.MIN_VALUE 会 long 溢出成负数，
    // 限频条件永远不成立。用 -100 让第一帧就输出一次。
    private static long lastDebugTick = -100L;
    private static long lastMatrixTick = -100L;
    private static long frameSequence;
    private static boolean missingShipLogged;

    private ShipWaterRenderer() {
    }

    private static boolean readDebugDefault() {
        String property = System.getProperty("barotrauma.waterDebug");
        String env = System.getenv("BAROTRAUMA_WATER_DEBUG");
        return truthy(property) || truthy(env);
    }

    private static boolean truthy(String value) {
        return value != null && (value.equalsIgnoreCase("true") || value.equals("1")
                || value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("on"));
    }

    public static void accept(ShipWaterStatePacket packet) {
        state = packet;
        if (++packetCount % 40L == 0L) {
            BarotraumaMod.LOGGER.info("[water] 收到水面包：physical={} ship={} boxes={} anchor=({},{},{})",
                    packet.physical(), packet.shipId(), packet.boxes().length / 7,
                    packet.anchorX(), packet.anchorY(), packet.anchorZ());
        }
    }

    /**
     * 客户端判断某个位置是否在“未进水的船舱”里。
     *
     * <p>水面包里的盒子是装配坐标，渲染位置相对锚点的位移由 onRenderStage 每帧缓存；
     * 落在盒子里且高于该盒水面，就是干燥区。</p>
     */
    public static boolean isInsideDryZone(Level level, Vec3 pos) {
        if (pos == null) {
            return false;
        }
        ShipWaterStatePacket data = state;
        if (!data.physical() && OceanEnvironment.isInsideStarterSubDryZone(pos)) {
            // 初始潜艇位置固定，内腔范围也是固定的；水面包只用来提供“内腔灌到多高”。
            // 没有水面包（刚进世界/核心还没扫）时按干燥兜底。
            return isStarterCavityDry(data.boxes(), pos.y);
        }
        if (data.boxes().length < 7) {
            return false;
        }
        if (data.physical() && !lastShipFound) {
            return false;
        }
        double lx = pos.x - lastDeltaX;
        double ly = pos.y - lastDeltaY;
        double lz = pos.z - lastDeltaZ;
        return insideDryBox(data.boxes(), lx, ly, lz);
    }

    /**
     * 初始潜艇内腔是否仍在水面之上。
     *
     * <p>只在覆盖初始潜艇的那几个盒子里找水面；没有一个盒子覆盖它说明水面包还没到，
     * 按干燥处理。这样破壳进水后水面升高会正常把滤镜放进舱里。</p>
     */
    private static boolean isStarterCavityDry(float[] boxes, double eyeY) {
        for (int i = 0; i + 6 < boxes.length; i += 7) {
            float minX = boxes[i];
            float maxX = boxes[i + 3];
            float minZ = boxes[i + 2];
            float maxZ = boxes[i + 5];
            // 盒子和初始潜艇内腔在 XZ 上有重叠，就认为它描述的是这艘船。
            if (maxX + 1.0F < StarterSub.ORIGIN_X + 1
                    || minX > StarterSub.ORIGIN_X + StarterSub.SIZE_X - 1
                    || maxZ + 1.0F < StarterSub.ORIGIN_Z + 1
                    || minZ > StarterSub.ORIGIN_Z + StarterSub.SIZE_Z - 1) {
                continue;
            }
            if (eyeY <= boxes[i + 6] + 0.01F) {
                return false;
            }
        }
        return true;
    }

    /** 通用盒测试：位置在舱室包围盒内且高于水面。干燥舱室允许跳跃时眼睛略微越过顶格。 */
    private static boolean insideDryBox(float[] boxes, double px, double py, double pz) {
        for (int i = 0; i + 6 < boxes.length; i += 7) {
            float minX = boxes[i];
            float minY = boxes[i + 1];
            float minZ = boxes[i + 2];
            float maxX = boxes[i + 3] + 1.0F;
            float maxY = boxes[i + 4] + 1.0F;
            float maxZ = boxes[i + 5] + 1.0F;
            float surface = boxes[i + 6];
            boolean dryBox = surface <= minY + 0.01F;
            float top = maxY + (dryBox ? 1.0F : 0.0F);
            if (px >= minX && px <= maxX
                    && py >= minY && py <= top
                    && pz >= minZ && pz <= maxZ
                    && py > surface + 0.01F) {
                return true;
            }
        }
        return false;
    }

    /** F9 切换诊断；默认关闭，不需要重启客户端。 */
    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS || event.getKey() != GLFW.GLFW_KEY_F9) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) {
            return;
        }
        debug = !debug;
        BarotraumaMod.LOGGER.info("[water] 诊断模式：{}", debug ? "开" : "关");
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[water] 诊断模式：" + (debug ? "开" : "关")), true);
        }
    }

    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }
        ShipWaterStatePacket data = state;
        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();

        ClientShip ship = null;
        if (data.physical() && data.shipId() >= 0) {
            ship = findClientShip(level, data.shipId());
            if (ship == null) {
                if (!missingShipLogged) {
                    missingShipLogged = true;
                    BarotraumaMod.LOGGER.warn("[water] 客户端找不到船 id={}", data.shipId());
                }
                lastShipFound = false;
                return;
            }
        }

        float dx = 0.0F;
        float dy = 0.0F;
        float dz = 0.0F;
        if (ship != null) {
            Vector3dc now = ship.getRenderTransform().getPositionInWorld();
            dx = (float) (now.x() - data.anchorX());
            dy = (float) (now.y() - data.anchorY());
            dz = (float) (now.z() - data.anchorZ());
        }
        lastDeltaX = dx;
        lastDeltaY = dy;
        lastDeltaZ = dz;
        lastShipFound = true;

        // 空气海域：不再画水体。保留下面的旧渲染代码只是为了保留诊断/回退参考，
        // 正常路径在这里直接返回。
        if (!RENDER_WATER) {
            return;
        }

        // 原有的 1Hz 运行日志保持不变（不是新的详细诊断）。
        if (level.getGameTime() - lastDebugTick >= 20L) {
            lastDebugTick = level.getGameTime();
            BarotraumaMod.LOGGER.info("[water] 画水面：ship={} physical={} boxes={} delta=({},{},{}) firstSurface={}",
                    data.shipId(), data.physical(), data.boxes().length / 7, dx, dy, dz,
                    data.boxes().length >= 7 ? data.boxes()[6] + dy : Float.NaN);
        }

        // ---- 独立修复补丁：相机相对坐标 + debugQuads（NO_CULL）----
        // 第 0 步的 drawWaterLegacy 保留，可用 -Dbarotrauma.waterFix=false 回退。
        WaterStats water = FIX_MAIN_WATER
                ? drawWaterFixed(event, mc, data, dx, dy, dz, ship, cameraPos)
                : drawWaterLegacy(event, mc, data, dx, dy, dz, ship != null);

        // ---- 诊断显示：独立 buffer，绝不污染主水路径的 flush ----
        MarkerStats markers = null;
        if (debug) {
            try {
                markers = drawDiagnostics(event, camera, cameraPos);
            } catch (Throwable failure) {
                logDiagnosticFailure("诊断标记渲染失败", failure);
            }
        }

        if (debug && level.getGameTime() - lastMatrixTick >= 20L) {
            lastMatrixTick = level.getGameTime();
            try {
                logDiagnostics(event, mc, camera, cameraPos, data, ship, dx, dy, dz, water, markers);
            } catch (Throwable failure) {
                logDiagnosticFailure("诊断日志失败", failure);
            }
        }
    }

    private static boolean diagnosticErrorLogged;

    private static void logDiagnosticFailure(String what, Throwable failure) {
        if (!diagnosticErrorLogged) {
            diagnosticErrorLogged = true;
            BarotraumaMod.LOGGER.warn("[water] {}（只记录一次；主水面路径不受影响）", what, failure);
        }
    }

    private static ClientShip findClientShip(ClientLevel level, long shipId) {
        for (Ship candidate : org.valkyrienskies.mod.common.VSGameUtilsKt.getShipObjectWorld(level).getAllShips()) {
            if (candidate.getId() == shipId && candidate instanceof ClientShip client) {
                return client;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 原水体路径

    private record WaterStats(int quads, int vertices, int boxesDrawn) {
    }

    /**
     * 原来的渲染代码（2026-11-02 14:20 之前的版本），坐标和 RenderType 都保持原样。
     * 这个方法是"第 0 步只加诊断"的基准，修复补丁会单独替换它。
     */
    private static WaterStats drawWaterLegacy(RenderLevelStageEvent event, Minecraft mc,
                                              ShipWaterStatePacket data,
                                              float dx, float dy, float dz, boolean shipFound) {
        if (data.boxes().length < 7) {
            return new WaterStats(0, 0, 0);
        }
        if (data.physical() && !shipFound) {
            return new WaterStats(0, 0, 0);
        }

        PoseStack pose = event.getPoseStack();
        Matrix4f m = pose.last().pose();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderType.debugFilledBox());
        float r = 0.05F;
        float g = 0.28F;
        float b = 0.42F;
        float a = 0.42F;

        int quads = 0;
        int boxesDrawn = 0;
        for (int i = 0; i + 6 < data.boxes().length; i += 7) {
            float minX = data.boxes()[i] + dx;
            float minY = data.boxes()[i + 1] + dy;
            float minZ = data.boxes()[i + 2] + dz;
            float maxX = data.boxes()[i + 3] + 1.0F + dx;
            float maxY = data.boxes()[i + 4] + 1.0F + dy;
            float maxZ = data.boxes()[i + 5] + 1.0F + dz;
            float surface = data.boxes()[i + 6] + dy;
            if (surface <= minY + 0.01F) {
                continue;
            }
            if (surface > maxY) {
                surface = maxY;
            }
            float seconds = (mc.level.getGameTime() % 200000L) / 20.0F;
            int nx = Math.max(1, Math.min(32, (int) Math.ceil(maxX - minX)));
            int nz = Math.max(1, Math.min(32, (int) Math.ceil(maxZ - minZ)));
            float stepX = (maxX - minX) / nx;
            float stepZ = (maxZ - minZ) / nz;
            for (int ix = 0; ix < nx; ix++) {
                for (int iz = 0; iz < nz; iz++) {
                    float xa = minX + ix * stepX;
                    float xb = xa + stepX;
                    float za = minZ + iz * stepZ;
                    float zb = za + stepZ;
                    float ha = wave(seconds, xa, za);
                    float hb = wave(seconds, xa, zb);
                    float hc = wave(seconds, xb, zb);
                    float hd = wave(seconds, xb, za);
                    float slope = (hb + hc - ha - hd) * 6.0F;
                    float shade = Math.max(0.45F, Math.min(1.35F, 1.0F + slope));
                    waveQuad(vc, m, r * shade, g * shade, b * shade, a,
                            xa, surface + ha, za, xa, surface + hb, zb,
                            xb, surface + hc, zb, xb, surface + hd, za);
                    quads++;
                }
            }
            quad(vc, m, r, g, b, a * 0.8F, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ);
            quad(vc, m, r, g, b, a * 0.8F, minX, minY, minZ, minX, surface, minZ, maxX, surface, minZ, maxX, minY, minZ);
            quad(vc, m, r, g, b, a * 0.8F, minX, minY, maxZ, maxX, minY, maxZ, maxX, surface, maxZ, minX, surface, maxZ);
            quad(vc, m, r, g, b, a * 0.8F, minX, minY, minZ, minX, minY, maxZ, minX, surface, maxZ, minX, surface, minZ);
            quad(vc, m, r, g, b, a * 0.8F, maxX, minY, minZ, maxX, surface, minZ, maxX, surface, maxZ, maxX, minY, maxZ);
            quads += 5;
            boxesDrawn++;
        }
        if (quads > 0) {
            // 注意：下面这几个手动状态调用在 debugFilledBox 上会被 RenderType.setupRenderState
            // 覆盖（CULL / LEQUAL / COLOR_DEPTH_WRITE）。这里保持原样，作为第 0 步的基准。
            RenderSystem.disableCull();
            RenderSystem.depthMask(false);
            buffers.endBatch(RenderType.debugFilledBox());
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
        }
        return new WaterStats(quads, quads * 4, boxesDrawn);
    }

    /**
     * 修复后的主水体路径：QUADS + NO_CULL，顶点从装配原坐标经"当前 render 变换"映射到
     * 相机相对坐标，再交给事件 PoseStack（它只含相机旋转）。
     *
     * <p>映射公式：{@code camRel = shipToWorld * translate(positionInShip − anchor) * v − camera}。
     * 展开后等价于 {@code R*(v − anchor) + renderPos − camera}：绕装配锚点旋转、平移到当前
     * render 位置、再减相机。没有把 shipToWorld 再乘进 PoseStack，所以不会双重旋转。</p>
     */
    private static WaterStats drawWaterFixed(RenderLevelStageEvent event, Minecraft mc,
                                             ShipWaterStatePacket data,
                                             float dx, float dy, float dz,
                                             ClientShip ship, Vec3 cameraPos) {
        if (data.boxes().length < 7) {
            return new WaterStats(0, 0, 0);
        }
        Matrix4d toCamera;
        if (data.physical()) {
            if (ship == null) {
                return new WaterStats(0, 0, 0);
            }
            toCamera = waterToCamera(ship, cameraPos, data);
        } else {
            // 方块船没有 render transform：世界坐标 = 装配原坐标 + 整数位移。
            toCamera = new Matrix4d().translation(
                    dx - cameraPos.x, dy - cameraPos.y, dz - cameraPos.z);
        }

        PoseStack pose = event.getPoseStack();
        Matrix4f m = pose.last().pose();
        // 独立 buffer：不要和声纳/其它 debugQuads 共用 mc.renderBuffers().bufferSource()，
        // 否则 endBatch(debugQuads) 可能把别人的 pending 顶点一起画掉。
        BufferBuilder waterBuilder = new BufferBuilder(4096);
        MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(waterBuilder);
        RenderType type = RenderType.debugQuads();
        VertexConsumer vc = buffers.getBuffer(type);
        float r = 0.05F;
        float g = 0.28F;
        float b = 0.42F;
        float a = 0.42F;

        int quads = 0;
        int boxesDrawn = 0;
        for (int i = 0; i + 6 < data.boxes().length; i += 7) {
            float minX = data.boxes()[i];
            float minY = data.boxes()[i + 1];
            float minZ = data.boxes()[i + 2];
            float maxX = data.boxes()[i + 3] + 1.0F;
            float maxY = data.boxes()[i + 4] + 1.0F;
            float maxZ = data.boxes()[i + 5] + 1.0F;
            float surface = data.boxes()[i + 6];
            if (surface <= minY + 0.01F) {
                continue;
            }
            if (surface > maxY) {
                surface = maxY;
            }
            float seconds = (mc.level.getGameTime() % 200000L) / 20.0F;
            int nx = Math.max(1, Math.min(32, (int) Math.ceil(maxX - minX)));
            int nz = Math.max(1, Math.min(32, (int) Math.ceil(maxZ - minZ)));
            float stepX = (maxX - minX) / nx;
            float stepZ = (maxZ - minZ) / nz;
            for (int ix = 0; ix < nx; ix++) {
                for (int iz = 0; iz < nz; iz++) {
                    float xa = minX + ix * stepX;
                    float xb = xa + stepX;
                    float za = minZ + iz * stepZ;
                    float zb = za + stepZ;
                    float ha = wave(seconds, xa, za);
                    float hb = wave(seconds, xa, zb);
                    float hc = wave(seconds, xb, zb);
                    float hd = wave(seconds, xb, za);
                    float slope = (hb + hc - ha - hd) * 6.0F;
                    float shade = Math.max(0.45F, Math.min(1.35F, 1.0F + slope));
                    waveQuadXf(vc, m, toCamera, r * shade, g * shade, b * shade, a,
                            xa, surface + ha, za, xa, surface + hb, zb,
                            xb, surface + hc, zb, xb, surface + hd, za);
                    quads++;
                }
            }
            quadXf(vc, m, toCamera, r, g, b, a * 0.8F,
                    minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ);
            quadXf(vc, m, toCamera, r, g, b, a * 0.8F,
                    minX, minY, minZ, minX, surface, minZ, maxX, surface, minZ, maxX, minY, minZ);
            quadXf(vc, m, toCamera, r, g, b, a * 0.8F,
                    minX, minY, maxZ, maxX, minY, maxZ, maxX, surface, maxZ, minX, surface, maxZ);
            quadXf(vc, m, toCamera, r, g, b, a * 0.8F,
                    minX, minY, minZ, minX, minY, maxZ, minX, surface, maxZ, minX, surface, minZ);
            quadXf(vc, m, toCamera, r, g, b, a * 0.8F,
                    maxX, minY, minZ, maxX, surface, minZ, maxX, surface, maxZ, maxX, minY, maxZ);
            quads += 5;
            boxesDrawn++;
        }
        if (quads > 0) {
            // 不再手动 disableCull/depthMask：debugQuads 的 state shard 已明确是
            // NO_CULL + TRANSLUCENT_TRANSPARENCY + LEQUAL_DEPTH_TEST + COLOR_DEPTH_WRITE。
            // debugQuads 没有 VIEW_OFFSET_Z_LAYERING（RenderType.java:141/142），这里显式复刻
            // 该 shard 的 modelview 缩放（RenderStateShard.java:162-171），避免与船壳共面时 z-fighting。
            PoseStack modelView = RenderSystem.getModelViewStack();
            modelView.pushPose();
            // 事件时全局 modelview 是 identity（Forge LevelRenderer.java:1339-1340/1385-1387），
            // 这里显式 setIdentity 保证顶点里的 pose 只被应用一次。
            modelView.setIdentity();
            modelView.scale(0.99975586F, 0.99975586F, 0.99975586F);
            RenderSystem.applyModelViewMatrix();
            try {
                buffers.endBatch(type);
            } finally {
                modelView.popPose();
                RenderSystem.applyModelViewMatrix();
            }
        }
        return new WaterStats(quads, quads * 4, boxesDrawn);
    }

    /** 只在渲染线程使用；避免每个顶点分配 Vector3d。 */
    private static final Vector3d WATER_SCRATCH = new Vector3d();

    private static void waterVertex(VertexConsumer vc, Matrix4f pose, Matrix4d toCamera,
                                    float x, float y, float z,
                                    float r, float g, float b, float a) {
        WATER_SCRATCH.set(x, y, z);
        toCamera.transformPosition(WATER_SCRATCH);
        vc.vertex(pose, (float) WATER_SCRATCH.x, (float) WATER_SCRATCH.y, (float) WATER_SCRATCH.z)
                .color(r, g, b, a)
                .endVertex();
    }

    private static void waveQuadXf(VertexConsumer vc, Matrix4f pose, Matrix4d toCamera,
                                   float r, float g, float b, float a,
                                   float x1, float y1, float z1, float x2, float y2, float z2,
                                   float x3, float y3, float z3, float x4, float y4, float z4) {
        waterVertex(vc, pose, toCamera, x1, y1, z1, r, g, b, a);
        waterVertex(vc, pose, toCamera, x2, y2, z2, r, g, b, a);
        waterVertex(vc, pose, toCamera, x3, y3, z3, r, g, b, a);
        waterVertex(vc, pose, toCamera, x4, y4, z4, r, g, b, a);
    }

    private static void quadXf(VertexConsumer vc, Matrix4f pose, Matrix4d toCamera,
                               float r, float g, float b, float a,
                               float x1, float y1, float z1, float x2, float y2, float z2,
                               float x3, float y3, float z3, float x4, float y4, float z4) {
        waterVertex(vc, pose, toCamera, x1, y1, z1, r, g, b, a);
        waterVertex(vc, pose, toCamera, x2, y2, z2, r, g, b, a);
        waterVertex(vc, pose, toCamera, x3, y3, z3, r, g, b, a);
        waterVertex(vc, pose, toCamera, x4, y4, z4, r, g, b, a);
    }

    // ------------------------------------------------------------------ 诊断显示

    private record MarkerStats(int productionQuads, int rawDepthOnQuads, int rawDepthOffQuads,
                               int absoluteQuads, int vertices) {
    }

    /**
     * 视线前方 2m 的三个 0.2m 测试面 + 深度对照。
     *
     * <p>所有标记都以"相机相对坐标"提交（world − camera），因为 RenderLevelStageEvent 的
     * PoseStack 只有相机旋转、没有相机平移（见 Forge 47.4.0 GameRenderer.renderLevel 与
     * LevelRenderer.renderChunkLayer）。另外画一个"绝对世界坐标"对照面，正常情况下应该
     * 出现在远处/画面外——如果它反而出现在眼前，说明我们对坐标空间的理解错了。</p>
     */
    private static MarkerStats drawDiagnostics(RenderLevelStageEvent event, Camera camera, Vec3 cameraPos) {
        Vec3 forward = new Vec3(camera.getLookVector()).normalize();
        Vec3 up = new Vec3(camera.getUpVector()).normalize();
        Vec3 right = new Vec3(camera.getLeftVector()).normalize().scale(-1.0D);
        Vec3 base = cameraPos.add(forward.scale(2.0D));

        Vec3[] centers = {
                base,
                base.add(right.scale(0.30D)),
                base.add(right.scale(0.60D))
        };
        float[][] colors = {
                {1.00F, 0.25F, 0.25F, 0.90F},
                {0.25F, 1.00F, 0.35F, 0.90F},
                {0.30F, 0.55F, 1.00F, 0.90F}
        };

        // 三个位置的"现有管线"标记用一个独立 buffer，避免碰主水路径的 buffer。
        Matrix4f pose = event.getPoseStack().last().pose();
        BufferBuilder productionBuilder = new BufferBuilder(256);
        MultiBufferSource.BufferSource production = MultiBufferSource.immediate(productionBuilder);
        VertexConsumer productionVc = production.getBuffer(RenderType.debugFilledBox());
        int productionQuads = 0;
        for (int i = 0; i < centers.length; i++) {
            Vec3[] quad = markerQuad(centers[i], right, up, 0.10D);
            for (Vec3 v : quad) {
                Vec3 rel = v.subtract(cameraPos);
                productionVc.vertex(pose, (float) rel.x, (float) rel.y, (float) rel.z)
                        .color(colors[i][0], colors[i][1], colors[i][2], colors[i][3])
                        .endVertex();
            }
            productionQuads++;
        }
        // 生产标记的顶点已经乘了事件 pose；这里显式 identity，避免全局 modelview 再叠一次。
        PoseStack productionModelView = RenderSystem.getModelViewStack();
        productionModelView.pushPose();
        productionModelView.setIdentity();
        RenderSystem.applyModelViewMatrix();
        try {
            production.endBatch(RenderType.debugFilledBox());
        } finally {
            productionModelView.popPose();
            RenderSystem.applyModelViewMatrix();
        }

        // 同位置配对：诊断显式 QUADS 管线（NO_CULL + 深度测试开 + 不写深度）。
        int rawDepthOn = 0;
        for (int i = 0; i < centers.length; i++) {
            Vec3[] quad = markerQuad(centers[i], right, up, 0.10D);
            drawRawQuad(event, cameraPos, quad, colors[i][0] * 0.9F, colors[i][1] * 0.9F,
                    colors[i][2] * 0.9F, 0.55F, true, false);
            rawDepthOn++;
        }

        // 深度对照：同一位置，一个开深度测试、一个关深度测试（品红/黄）。
        Vec3 depthCenter = base.add(right.scale(0.90D));
        Vec3[] depthQuad = markerQuad(depthCenter, right, up, 0.10D);
        drawRawQuad(event, cameraPos, depthQuad, 1.00F, 0.20F, 0.20F, 0.90F, true, false);
        drawRawQuad(event, cameraPos, depthQuad, 1.00F, 0.90F, 0.10F, 0.90F, false, false);
        int rawDepthOff = 1;
        rawDepthOn++;

        // 坐标空间对照：同一个"相机前 2m"位置，但顶点直接提交绝对世界坐标。
        Vec3 absoluteCenter = base.add(right.scale(1.20D));
        Vec3[] absoluteQuad = markerQuad(absoluteCenter, right, up, 0.10D);
        drawRawQuad(event, Vec3.ZERO, absoluteQuad, 0.85F, 0.20F, 0.95F, 0.90F, false, false);

        int vertices = productionQuads * 4 + (rawDepthOn + rawDepthOff) * 4 + 4;
        return new MarkerStats(productionQuads, rawDepthOn, rawDepthOff, 1, vertices);
    }

    /** 相机右/上平面上的一个 0.2m × 0.2m 四边形（世界坐标）。 */
    private static Vec3[] markerQuad(Vec3 center, Vec3 right, Vec3 up, double half) {
        return new Vec3[]{
                center.add(right.scale(-half)).add(up.scale(-half)),
                center.add(right.scale(half)).add(up.scale(-half)),
                center.add(right.scale(half)).add(up.scale(half)),
                center.add(right.scale(-half)).add(up.scale(half))
        };
    }

    /**
     * 诊断用显式管线：QUADS + NO_CULL + 可选深度测试/深度写入 + 半透明。
     *
     * <p>不用 RenderType.debugFilledBox（TRIANGLE_STRIP，多个独立 quad 会连成错误的三角形），
     * 也不依赖 state shard 的覆盖行为——这里所有 GL 状态都是显式设置、显式恢复。</p>
     */
    private static void drawRawQuad(RenderLevelStageEvent event, Vec3 cameraPos, Vec3[] worldQuad,
                                    float r, float g, float b, float a,
                                    boolean depthTest, boolean depthWrite) {
        Matrix4f pose = event.getPoseStack().last().pose();
        BufferBuilder builder = new BufferBuilder(256);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (Vec3 v : worldQuad) {
            Vec3 rel = v.subtract(cameraPos);
            // 顶点只存相机相对坐标；pose 只通过下面的 modelview 应用一次。
            // 不能同时 vertex(pose,...) + modelView.mulPoseMatrix(pose) —— 会旋转两次。
            builder.vertex((float) rel.x, (float) rel.y, (float) rel.z)
                    .color(r, g, b, a)
                    .endVertex();
        }
        BufferBuilder.RenderedBuffer rendered = builder.end();

        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        com.mojang.blaze3d.vertex.VertexSorting previousSorting = RenderSystem.getVertexSorting();
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.setIdentity();
        modelView.mulPoseMatrix(pose);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(event.getProjectionMatrix(), com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        if (depthTest) {
            RenderSystem.enableDepthTest();
        } else {
            RenderSystem.disableDepthTest();
        }
        RenderSystem.depthMask(depthWrite);
        try {
            BufferUploader.drawWithShader(rendered);
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
        }
    }

    // ------------------------------------------------------------------ 诊断日志

    private static void logDiagnostics(RenderLevelStageEvent event, Minecraft mc, Camera camera, Vec3 cameraPos,
                                       ShipWaterStatePacket data, ClientShip ship,
                                       float dx, float dy, float dz,
                                       WaterStats water, MarkerStats markers) {
        StringBuilder sb = new StringBuilder(2048);
        long gameTime = mc.level == null ? 0L : mc.level.getGameTime();
        float partialTick = event.getPartialTick();
        sb.append("\n[water-diag] frame=").append(++frameSequence)
                .append(" gameTime=").append(gameTime)
                .append(" renderTick=").append(event.getRenderTick())
                .append(" partialTick=").append(fmt(partialTick))
                .append(" stage=").append(event.getStage());
        sb.append("\n[water-diag] camera=").append(fmt(cameraPos))
                .append(" cameraEntity=").append(describeEntity(mc.getCameraEntity()))
                .append(" camType=").append(mc.options.getCameraType())
                .append(" eye=").append(fmt(mc.player.getEyePosition(partialTick)))
                .append(" playerPos=").append(fmt(mc.player.position()))
                .append(" playerBlockY=").append(mc.player.blockPosition().getY())
                .append(" playerY=").append(fmt(mc.player.getY()));
        sb.append("\n[water-diag] packet: physical=").append(data.physical())
                .append(" shipId=").append(data.shipId())
                .append(" boxes=").append(data.boxes().length / 7)
                .append(" anchor=").append(fmt(data.anchorX(), data.anchorY(), data.anchorZ()))
                .append(" delta=").append(fmt(dx, dy, dz));
        if (ship != null) {
            ShipTransform render = ship.getRenderTransform();
            ShipTransform current = ship.getTransform();
            ShipTransform previous = ship.getPrevTickTransform();
            sb.append("\n[water-diag] ship=").append(ship.getId())
                    .append(" renderPos=").append(fmt(render.getPositionInWorld()))
                    .append(" renderPosInShip=").append(fmt(render.getPositionInShip()))
                    .append(" renderRot=").append(fmt(render.getShipToWorldRotation()))
                    .append(" renderScale=").append(fmt(render.getShipToWorldScaling()))
                    .append(" currentPos=").append(fmt(current.getPositionInWorld()))
                    .append(" currentPosInShip=").append(fmt(current.getPositionInShip()))
                    .append(" currentRot=").append(fmt(current.getShipToWorldRotation()))
                    .append(" currentScale=").append(fmt(current.getShipToWorldScaling()))
                    .append(" prevPos=").append(fmt(previous.getPositionInWorld()))
                    .append(" prevPosInShip=").append(fmt(previous.getPositionInShip()))
                    .append(" prevRot=").append(fmt(previous.getShipToWorldRotation()))
                    .append(" prevScale=").append(fmt(previous.getShipToWorldScaling()));
            appendReferenceMapping(sb, render, data);
        }
        if (data.boxes().length >= 7) {
            int count = Math.min(3, data.boxes().length / 7);
            for (int i = 0; i < count; i++) {
                int o = i * 7;
                sb.append("\n[water-diag] box[").append(i).append("] min=(")
                        .append(fmt(data.boxes()[o])).append(',')
                        .append(fmt(data.boxes()[o + 1])).append(',')
                        .append(fmt(data.boxes()[o + 2])).append(") max=(")
                        .append(fmt(data.boxes()[o + 3])).append(',')
                        .append(fmt(data.boxes()[o + 4])).append(',')
                        .append(fmt(data.boxes()[o + 5])).append(") surface=")
                        .append(fmt(data.boxes()[o + 6]))
                        .append(" surface+dy=").append(fmt(data.boxes()[o + 6] + dy));
            }
        }
        RenderType mainType = FIX_MAIN_WATER ? RenderType.debugQuads() : RenderType.debugFilledBox();
        sb.append("\n[water-diag] main: fixEnabled=").append(FIX_MAIN_WATER)
                .append(" type=").append(mainType)
                .append(" mode=").append(mainType.mode())
                .append(" format=").append(mainType.format())
                .append(" quads=").append(water.quads())
                .append(" vertices=").append(water.vertices())
                .append(" boxesDrawn=").append(water.boxesDrawn())
                .append(" flush=").append(water.quads() > 0
                        ? "endBatch(" + (FIX_MAIN_WATER ? "debug_quads" : "debug_filled_box") + ")"
                        : "none")
                .append(" stage=").append(event.getStage())
                .append(" expectedState=").append(FIX_MAIN_WATER
                        ? "cull=NO_CULL,transparency=TRANSLUCENT,depthTest=LEQUAL,depthWrite=COLOR_DEPTH_WRITE(源码核实)"
                        : "cull=CULL,transparency=TRANSLUCENT,depthTest=LEQUAL,depthWrite=COLOR_DEPTH_WRITE;手动disableCull/depthMask会被setupRenderState覆盖(源码核实)");
        if (markers != null) {
            sb.append("\n[water-diag] markers: productionType=").append(RenderType.debugFilledBox())
                    .append(" mode=").append(RenderType.debugFilledBox().mode())
                    .append(" productionQuads=").append(markers.productionQuads())
                    .append(" rawQuadsDepthOn=").append(markers.rawDepthOnQuads())
                    .append(" rawQuadsDepthOff=").append(markers.rawDepthOffQuads())
                    .append(" absoluteWorldQuads=").append(markers.absoluteQuads())
                    .append(" vertices=").append(markers.vertices())
                    .append(" flush=").append("production.endBatch+raw BufferUploader.drawWithShader")
                    .append(" stage=").append(event.getStage());
        }
        // 矩阵每 100 tick（5 秒）一次，避免刷屏。
        boolean matrixFrame = (gameTime % 100L) < 20L;
        if (matrixFrame) {
            appendMatrix(sb, "cpuPose", event.getPoseStack().last().pose());
            appendMatrix(sb, "cpuProjection", event.getProjectionMatrix());
            appendMatrix(sb, "gpuModelView", RenderSystem.getModelViewMatrix());
            appendMatrix(sb, "gpuProjection", RenderSystem.getProjectionMatrix());
        }
        BarotraumaMod.LOGGER.info(sb.toString());
    }

    /**
     * 用一个具体坐标验证"装配原坐标 → 船坞坐标 → render shipToWorld"的对齐。
     *
     * <p>参考点取第一个水面盒的 min 角（装配原坐标）。船坞偏移由
     * {@code renderTransform.positionInShip − anchor} 反推（装配时若旋转为单位阵，
     * 这个关系成立）。同时给出"仅平移 delta"的结果，两者之差就是旋转带来的偏差。</p>
     */
    private static void appendReferenceMapping(StringBuilder sb, ShipTransform render, ShipWaterStatePacket data) {
        if (data.boxes().length < 7) {
            return;
        }
        Vector3dc positionInShip = render.getPositionInShip();
        Vector3d reference = new Vector3d(data.boxes()[0], data.boxes()[1], data.boxes()[2]);
        Vector3d yardOffset = new Vector3d(positionInShip)
                .sub(data.anchorX(), data.anchorY(), data.anchorZ());
        Vector3d yard = new Vector3d(reference).add(yardOffset);
        Vector3d viaShipToWorld = render.getShipToWorld().transformPosition(new Vector3d(yard), new Vector3d());
        Vector3d viaDelta = new Vector3d(reference)
                .add(render.getPositionInWorld())
                .sub(data.anchorX(), data.anchorY(), data.anchorZ());
        Vector3d diff = new Vector3d(viaShipToWorld).sub(viaDelta);
        Vector3d shipOrigin = render.getShipToWorld()
                .transformPosition(new Vector3d(positionInShip), new Vector3d());
        Vector3d originDiff = shipOrigin.sub(render.getPositionInWorld());
        sb.append("\n[water-diag] ref: orig=").append(fmt(reference))
                .append(" yardOffset=").append(fmt(yardOffset))
                .append(" yard=").append(fmt(yard))
                .append(" viaShipToWorld=").append(fmt(viaShipToWorld))
                .append(" viaDelta=").append(fmt(viaDelta))
                .append(" diff=").append(fmt(diff))
                .append(" shipToWorld(posInShip)-posInWorld=").append(fmt(originDiff));
    }

    private static Matrix4d waterToCamera(ClientShip ship, Vec3 cameraPos, ShipWaterStatePacket data) {
        ShipTransform render = ship.getRenderTransform();
        Vector3dc positionInShip = render.getPositionInShip();
        Matrix4d matrix = new Matrix4d();
        matrix.translation(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        matrix.mul(render.getShipToWorld());
        matrix.translate(positionInShip.x() - data.anchorX(),
                positionInShip.y() - data.anchorY(),
                positionInShip.z() - data.anchorZ());
        return matrix;
    }

    private static void appendMatrix(StringBuilder sb, String name, Matrix4fc m) {
        sb.append("\n[water-diag] ").append(name).append("=[");
        for (int row = 0; row < 4; row++) {
            if (row > 0) {
                sb.append(';');
            }
            // JOML 是列主序：get(column, row)。
            sb.append(String.format(Locale.ROOT, "% .4f % .4f % .4f % .4f",
                    m.get(0, row), m.get(1, row), m.get(2, row), m.get(3, row)));
        }
        sb.append(']');
    }

    private static String describeEntity(net.minecraft.world.entity.Entity entity) {
        if (entity == null) {
            return "null";
        }
        return entity.getClass().getSimpleName() + "#" + entity.getId();
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String fmt(float value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String fmt(Vec3 vec) {
        return fmt(vec.x, vec.y, vec.z);
    }

    private static String fmt(float x, float y, float z) {
        return String.format(Locale.ROOT, "(%.4f,%.4f,%.4f)", x, y, z);
    }

    private static String fmt(double x, double y, double z) {
        return String.format(Locale.ROOT, "(%.4f,%.4f,%.4f)", x, y, z);
    }

    private static String fmt(Vector3dc vec) {
        return fmt(vec.x(), vec.y(), vec.z());
    }

    private static String fmt(Vector3f vec) {
        return fmt(vec.x(), vec.y(), vec.z());
    }

    private static String fmt(Quaterniondc quat) {
        return String.format(Locale.ROOT, "(%.4f,%.4f,%.4f,%.4f)",
                quat.x(), quat.y(), quat.z(), quat.w());
    }

    // ------------------------------------------------------------------ 几何辅助

    /** 波高：两组正弦叠加，幅度约 5~8 cm，不足以穿模，但视觉上有流动感。 */
    private static float wave(float seconds, float x, float z) {
        return (float) (Math.sin(seconds * 2.1F + x * 0.7F + z * 0.45F) * 0.05F
                + Math.sin(seconds * 1.37F - x * 0.33F + z * 0.61F) * 0.03F);
    }

    private static void waveQuad(VertexConsumer vc, Matrix4f m, float r, float g, float b, float a,
                                 float x1, float y1, float z1, float x2, float y2, float z2,
                                 float x3, float y3, float z3, float x4, float y4, float z4) {
        vc.vertex(m, x1, y1, z1).color(r, g, b, a).endVertex();
        vc.vertex(m, x2, y2, z2).color(r, g, b, a).endVertex();
        vc.vertex(m, x3, y3, z3).color(r, g, b, a).endVertex();
        vc.vertex(m, x4, y4, z4).color(r, g, b, a).endVertex();
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float r, float g, float b, float a,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float x3, float y3, float z3, float x4, float y4, float z4) {
        vc.vertex(m, x1, y1, z1).color(r, g, b, a).endVertex();
        vc.vertex(m, x2, y2, z2).color(r, g, b, a).endVertex();
        vc.vertex(m, x3, y3, z3).color(r, g, b, a).endVertex();
        vc.vertex(m, x4, y4, z4).color(r, g, b, a).endVertex();
    }
}
