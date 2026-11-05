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
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import com.opamb.barotrauma.registry.ModParticles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
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

    /**
     * 水面专用 RenderType：QUADS + NO_CULL + 半透明，但**不写深度**。
     *
     * <p>原版 {@code debugQuads()} 会写深度，水面后面的气泡/粒子会被整片挡掉；
     * 这里只写颜色，水后的粒子仍能透过来。</p>
     */
    private static final class WaterRenderType extends RenderStateShard {

        private WaterRenderType() {
            super("barotrauma_water_quads", () -> {
            }, () -> {
            });
        }

        private static final RenderType TYPE = RenderType.create(
                "barotrauma_water_quads",
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

    /**
     * 舱内水体绘制开关。空气海域仍然不生成真实水方块；这里画的是客户端自绘水面，
     * 数据来自 {@link ShipWaterStatePacket}。默认开启，可用 -Dbarotrauma.renderWater=false 关闭
     * （排查“数据没到”还是“渲染没画”时用）。
     */
    private static final boolean RENDER_WATER = !"false".equalsIgnoreCase(
            System.getProperty("barotrauma.renderWater", "true"));

    /**
     * 水体暴露面往舱内缩的厚度。
     *
     * <p>棱柱的顶面/侧面/底面正好贴在方块面上（满舱时水面贴天花板、侧面贴舱壁、
     * 底面贴地板），LEQUAL 下和舱壁 z-fight，看起来像水渗进了墙里。缩 1cm 后
     * 舱壁在水面后面正常显示，半透明水体叠在上面就是平滑的水线。</p>
     */
    private static final float WATER_INSET = 0.01F;

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
    /** 客户端平滑后的水面高度；服务端每 10 tick 才同步一次，直接画会一跳一跳。 */
    private static float[] smoothedBoxes = new float[0];
    private static long smoothedShipId = Long.MIN_VALUE;
    private static boolean smoothedPhysical;

    /**
     * 邻居候选表：只由**几何**决定，水位变化不改变候选集合。
     *
     * <p>原来 {@code neighborAcross} 每个 box × 4 个面、再加一次 {@code hasNeighborBelow}，
     * 每次都要全量扫一遍 boxes——连海空腔是按 1×1 竖列发的，一条船几百个 box，等于每帧
     * 几十万次比较。现在把"面相邻 + 重叠"这些纯几何条件在几何变化时算一次，存成
     * 候选下标池；每帧只对少量候选做"有没有水 / 取最高水面"的判定。</p>
     *
     * <p>槽位 = {@code boxIndex * 5 + face}，face 顺序同 {@link Face}：
     * BOTTOM/NORTH/SOUTH/WEST/EAST。</p>
     */
    private static int[] neighborStart = new int[0];
    private static int[] neighborCount = new int[0];
    private static int[] neighborPool = new int[0];
    private static long neighborGeometryHash;
    private static int neighborBoxCount = -1;

    /** 逐格水面/波浪系数：稠密网格，跨帧复用，不再每帧 new 两个 HashMap 也不做 Long 装箱。 */
    private static int cellOriginX;
    private static int cellOriginZ;
    private static int cellSpanX;
    private static int cellSpanZ;
    private static float[] cellTopGrid = new float[0];
    private static float[] cellScaleGrid = new float[0];
    private static boolean[] cellPresentGrid = new boolean[0];
    /** cornerAt 的输出缓冲，避免每次调用 new float[2]（水面网格每帧上千次）。 */
    private static final float[] CORNER_A = new float[2];
    private static final float[] CORNER_B = new float[2];
    private static final float[] CORNER_C = new float[2];
    private static final float[] CORNER_D = new float[2];

    private static long lastFrameNanos;
    private static double waveSeconds;
    private static long lastBreachParticleNanos;
    /** 满水舱气泡减半发送用。 */
    private static boolean fullBubbleToggle;
    /** 破口很多时轮转起点：每次从下一个洞开始发粒子，避免总是前几个洞喷。 */
    private static int breachRoundRobin;
    /** 已经打过水盒快照日志的船 id（一次性诊断）。 */
    private static long dumpedShipId = Long.MIN_VALUE;

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
            BarotraumaMod.LOGGER.info("[water] 收到水面包：physical={} ship={} boxes={} breaches={} anchor=({},{},{})",
                    packet.physical(), packet.shipId(), packet.boxes().length / 7,
                    packet.breaches().length / 8,
                    packet.anchorX(), packet.anchorY(), packet.anchorZ());
        }
        // 一次性诊断：新船的第一包水盒结构。渲染再出问题时直接对照真实数据。
        if (packet.shipId() != dumpedShipId && packet.boxes().length >= 7) {
            dumpedShipId = packet.shipId();
            float[] boxes = packet.boxes();
            int count = boxes.length / 7;
            java.util.TreeSet<Float> surfaces = new java.util.TreeSet<>();
            for (int i = 0; i + 6 < boxes.length; i += 7) {
                surfaces.add(boxes[i + 6]);
            }
            StringBuilder sb = new StringBuilder(1024);
            sb.append("[water] 水盒快照 ship=").append(packet.shipId())
                    .append(" boxes=").append(count)
                    .append(" 不同水面值=").append(surfaces.size());
            int shown = Math.min(12, count);
            for (int b = 0; b < shown; b++) {
                int o = b * 7;
                sb.append("\n[water]  box[").append(b).append("] (")
                        .append(boxes[o]).append(',').append(boxes[o + 1]).append(',')
                        .append(boxes[o + 2]).append(")-(")
                        .append(boxes[o + 3]).append(',').append(boxes[o + 4]).append(',')
                        .append(boxes[o + 5]).append(") surface=").append(boxes[o + 6]);
            }
            BarotraumaMod.LOGGER.info(sb.toString());
        }
    }

    /**
     * 这个位置是不是落在某个船舱的盒子里（不管进没进水）。
     *
     * <p>进水的舱室必须按"浸没"处理——哪怕船停在哨站干燥区里。</p>
     */
    public static boolean isInsideCompartment(Level level, Vec3 pos) {
        if (pos == null) {
            return false;
        }
        ShipWaterStatePacket data = state;
        if (!data.physical() && OceanEnvironment.isInsideStarterSubDryZone(pos)) {
            return true;
        }
        float[] boxes = data.boxes();
        if (boxes.length < 7) {
            return false;
        }
        if (data.physical() && !lastShipFound) {
            return false;
        }
        double lx = pos.x - lastDeltaX;
        double ly = pos.y - lastDeltaY;
        double lz = pos.z - lastDeltaZ;
        for (int i = 0; i + 6 < boxes.length; i += 7) {
            if (lx >= boxes[i] && lx <= boxes[i + 3] + 1.0F
                    && ly >= boxes[i + 1] && ly <= boxes[i + 4] + 1.0F
                    && lz >= boxes[i + 2] && lz <= boxes[i + 5] + 1.0F) {
                return true;
            }
        }
        return false;
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
            // 干燥盒子（水面=舱底）不算进水，别把站在舱底的人判成湿的。
            boolean dryBox = boxes[i + 6] <= boxes[i + 1] + 0.01F;
            if (!dryBox && eyeY <= boxes[i + 6] + 0.01F) {
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
                    // 干燥盒子整个都算干（水面就是舱底，脚底正好等于它）；
                    // 进过水的箱子仍然要求高于水面。
                    && (dryBox || py > surface + 0.01F)) {
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
        // 船下沉/移动时，舱内气泡要跟着水一起走，否则会留在原地显得“往上跑”。
        if (ship != null) {
            Vector3dc velocity = ship.getVelocity();
            ShipBubbleParticle.setShipCarry(velocity.x(), velocity.y(), velocity.z());
        } else {
            ShipBubbleParticle.setShipCarry(0.0D, 0.0D, 0.0D);
        }

        // 空气海域不生成真实水方块；舱内水面由下面的客户端自绘路径负责。
        // 需要临时关闭时用 -Dbarotrauma.renderWater=false。
        if (!RENDER_WATER) {
            return;
        }

        // 服务端 10 Hz 同步一次水面，这里插值到每一帧；波浪相位也按真实时间走，
        // 不再跟 20 TPS 的 gameTime 绑定，避免水面一跳一跳。
        float dt = frameDeltaSeconds();
        float[] boxes = smoothWater(data, dt);
        float seconds = (float) waveSeconds;

        // 运行日志降到 5 秒一条（原 1Hz）：长会话刷屏，日志体积和字符串分配都不划算。
        if (level.getGameTime() - lastDebugTick >= 100L) {
            lastDebugTick = level.getGameTime();
            BarotraumaMod.LOGGER.info("[water] 画水面：ship={} physical={} boxes={} breaches={} delta=({},{},{}) firstSurface={}",
                    data.shipId(), data.physical(), data.boxes().length / 7, data.breaches().length / 8,
                    dx, dy, dz,
                    data.boxes().length >= 7 ? data.boxes()[6] + dy : Float.NaN);
        }

        // ---- 独立修复补丁：相机相对坐标 + debugQuads（NO_CULL）----
        // 第 0 步的 drawWaterLegacy 保留，可用 -Dbarotrauma.waterFix=false 回退。
        WaterStats water = FIX_MAIN_WATER
                ? drawWaterFixed(event, mc.level, data, boxes, seconds, dx, dy, dz, ship, cameraPos)
                : drawWaterLegacy(event, mc, data, boxes, seconds, dx, dy, dz, ship != null);

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

    static ClientShip findClientShip(ClientLevel level, long shipId) {
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
                                              ShipWaterStatePacket data, float[] boxes,
                                              float seconds, float dx, float dy, float dz,
                                              boolean shipFound) {
        if (boxes.length < 7) {
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
        for (int i = 0; i + 6 < boxes.length; i += 7) {
            float minX = boxes[i] + dx;
            float minY = boxes[i + 1] + dy;
            float minZ = boxes[i + 2] + dz;
            float maxX = boxes[i + 3] + 1.0F + dx;
            float maxY = boxes[i + 4] + 1.0F + dy;
            float maxZ = boxes[i + 5] + 1.0F + dz;
            float surface = boxes[i + 6] + dy;
            if (surface <= minY + 0.01F) {
                continue;
            }
            if (surface > maxY) {
                surface = maxY;
            }
            int gridCap = boxes.length >= 7 * 64 ? 2
                    : boxes.length >= 7 * 16 ? 4 : 32;
            int nx = Math.max(1, Math.min(gridCap, (int) Math.ceil(maxX - minX)));
            int nz = Math.max(1, Math.min(gridCap, (int) Math.ceil(maxZ - minZ)));
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
    /**
     * 复用的水体/诊断 builder（同 vanilla {@code renderBuffers()} 的用法：每帧
     * getBuffer→endBatch）。之前每帧 {@code new BufferBuilder} 的 direct 内存要等 GC
     * 才释放，长时间游玩必然 native OOM（实测 8 分钟 {@code Failed to allocate ... bytes}）。
     */
    private static final BufferBuilder WATER_BUILDER = new BufferBuilder(4096);
    private static final MultiBufferSource.BufferSource WATER_BUFFERS =
            MultiBufferSource.immediate(WATER_BUILDER);
    private static final BufferBuilder DIAG_BUILDER = new BufferBuilder(256);
    private static final MultiBufferSource.BufferSource DIAG_BUFFERS =
            MultiBufferSource.immediate(DIAG_BUILDER);
    private static final BufferBuilder RAW_QUAD_BUILDER = new BufferBuilder(256);

    private static WaterStats drawWaterFixed(RenderLevelStageEvent event, ClientLevel level,
                                             ShipWaterStatePacket data, float[] boxes,
                                             float seconds, float dx, float dy, float dz,
                                             ClientShip ship, Vec3 cameraPos) {
        if (boxes.length < 7) {
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
        // builder 本身跨帧复用（见 WATER_BUILDER 注释）；万一上一帧异常把 builder 留在
        // begun 状态，这里先复位，避免 "Already building" 连环炸。
        if (WATER_BUILDER.building()) {
            WATER_BUILDER.discard();
        }
        RenderType type = WaterRenderType.TYPE;
        VertexConsumer vc = WATER_BUFFERS.getBuffer(type);
        float r = 0.05F;
        float g = 0.28F;
        float b = 0.42F;
        float a = 0.42F;

        int quads = 0;
        int boxesDrawn = 0;
        // 逐格可见水面高度/波浪系数：给"角点取四格平均"用。
        // 相邻水柱水位不同时（灌水过程中很正常），顶面角点按周围四格平均，
        // 相邻 quad 的共享边角点两边算出来完全相等——水面变成一张连续曲面，
        // 不再有台阶面，也就不会出现一格一条的"分界线"（用户实测）。
        //
        // 用一段覆盖全部盒子的紧凑网格（下标 = (x-minX)*spanZ + (z-minZ)），
        // 跨帧复用同一份数组；原来每帧两个 HashMap<Long,Float> 会产生上万次装箱。
        buildCellGrid(boxes);
        // 邻居候选表只在几何变化时重建（水位变了不影响"谁挨着谁"）。
        ensureNeighborTable(boxes);
        for (int i = 0; i + 6 < boxes.length; i += 7) {
            if (boxes[i + 6] <= boxes[i + 1] + 0.01F) {
                continue;
            }
            float top = boxDrawTop(boxes, i);
            float scale = boxWaveScale(boxes, i);
            for (int x = Math.round(boxes[i]); x <= Math.round(boxes[i + 3]); x++) {
                for (int z = Math.round(boxes[i + 2]); z <= Math.round(boxes[i + 5]); z++) {
                    putCellTop(x, z, top, scale);
                }
            }
        }
        for (int i = 0; i + 6 < boxes.length; i += 7) {
            float minX = boxes[i];
            float minY = boxes[i + 1];
            float minZ = boxes[i + 2];
            float maxX = boxes[i + 3] + 1.0F;
            float maxY = boxes[i + 4] + 1.0F;
            float maxZ = boxes[i + 5] + 1.0F;
            float surface = boxes[i + 6];
            if (surface <= minY + 0.01F) {
                continue;
            }
            // 一个舱室可能拆成多个棱柱。只有"水面落在自己这一层"的棱柱才画顶面；
            // 完全没在水下的棱柱（surface > maxY）顶面是水体内部，不画，否则会双倍混色。
            // 但 surface 异常超过 maxY（数据毛刺）时也必须画，否则顶上是一块干洞。
            float waterTop = Math.min(surface, maxY);
            boolean reachesSurface = surface <= maxY + 0.01F || waterTop >= maxY - 0.01F;
            // 满舱时 waterTop 贴着天花板，和方块面共面会 z-fight——画之前往舱内缩 1cm。
            float drawTop = waterTop;
            if (drawTop >= maxY - 0.01F) {
                drawTop -= WATER_INSET;
            }
            // 波浪幅度：贴天花板时压平（满舱没有自由液面），离天花板越近越小——
            // 波峰(+8cm)一旦戳进天花板方块就会被深度测试剔掉，顶上留一块块"干洞"。
            float waveScale = Math.max(0.0F, Math.min(1.0F, (maxY - 0.06F - drawTop) / 0.08F));
            // 顶面网格段数。侧面按同样的段数切：共享边必须由完全相同的角点构成，
            // 否则波浪位移会在顶面和水带之间留出三角裂缝——之前"水缝"的来源之一。
            int gridCap = boxes.length >= 7 * 64 ? 2
                    : boxes.length >= 7 * 16 ? 4 : 32;
            int nx = Math.max(1, Math.min(gridCap, (int) Math.ceil(maxX - minX)));
            int nz = Math.max(1, Math.min(gridCap, (int) Math.ceil(maxZ - minZ)));
            // ---- 顶面：角点高度 = 周围四格水面的平均（相邻 quad 共享角点数值相同），
            // 相邻水柱的水位差在格与格之间平滑过渡，不再有台阶面/一格一条的分界线；
            // 再叠加同相位的波浪。
            if (reachesSurface) {
                float stepX = (maxX - minX) / nx;
                float stepZ = (maxZ - minZ) / nz;
                for (int ix = 0; ix < nx; ix++) {
                    for (int iz = 0; iz < nz; iz++) {
                        float xa = minX + ix * stepX;
                        float xb = xa + stepX;
                        float za = minZ + iz * stepZ;
                        float zb = za + stepZ;
                        float[] ca = cornerAt(xa, za, drawTop, waveScale, CORNER_A);
                        float[] cb = cornerAt(xa, zb, drawTop, waveScale, CORNER_B);
                        float[] cc = cornerAt(xb, zb, drawTop, waveScale, CORNER_C);
                        float[] cd = cornerAt(xb, za, drawTop, waveScale, CORNER_D);
                        float ya = ca[0] + wave(seconds, xa, za) * ca[1];
                        float yb = cb[0] + wave(seconds, xa, zb) * cb[1];
                        float yc = cc[0] + wave(seconds, xb, zb) * cc[1];
                        float yd = cd[0] + wave(seconds, xb, za) * cd[1];
                        // 明暗用连续的空间/时间函数：相邻 quad 亮度差 <1%，不会出现分界线。
                        float xc = xa + stepX * 0.5F;
                        float zc = za + stepZ * 0.5F;
                        float shimmer = (float) (Math.sin(seconds * 1.3D + (xc + zc) * 0.25D) * 0.05D
                                + Math.sin(seconds * 0.9D - (xc - zc) * 0.18D) * 0.05D);
                        float shade = 1.0F + shimmer * (ca[1] + cb[1] + cc[1] + cd[1]) * 0.25F;
                        waveQuadXf(vc, m, toCamera, r * shade, g * shade, b * shade, a,
                                xa, ya, za, xa, yb, zb, xb, yc, zb, xb, yd, za);
                        quads++;
                    }
                }
            }
            // ---- 底面：贴地板才画；只沿法线（Y）缩 1cm，XZ 占满。
            // 之前 XZ 也缩 1cm 时，相邻列的底面之间留出 2cm 缝，从上面看地板上
            // 是一格一格的缝；现在相邻底面相接，无缝。
            if (!hasNeighborBelowCached(boxes, i)) {
                quadXf(vc, m, toCamera, r, g, b, a * 0.8F,
                        minX, minY + WATER_INSET, minZ,
                        maxX, minY + WATER_INSET, minZ,
                        maxX, minY + WATER_INSET, maxZ,
                        minX, minY + WATER_INSET, maxZ);
                quads++;
            }
            // ---- 四个侧面：只画"贴舱壁/临空"的那一侧（沿法线缩 1cm，沿墙方向占满整格）。
            // 相邻水柱之间不再画竖直台阶面——台阶面正是一格一条"分界线"的来源；
            // 水位差现在由顶面的角点平均平滑过渡，水面是一张连续曲面。
            int nIdx = neighborAcrossCached(boxes, i, Face.NORTH);
            if (nIdx < 0) {
                quads += wallStrip(vc, m, toCamera, r, g, b, a * 0.8F,
                        true, minX, maxX, minZ + WATER_INSET, minZ, minY,
                        nx, seconds, drawTop, waveScale);
            }
            nIdx = neighborAcrossCached(boxes, i, Face.SOUTH);
            if (nIdx < 0) {
                quads += wallStrip(vc, m, toCamera, r, g, b, a * 0.8F,
                        true, minX, maxX, maxZ - WATER_INSET, maxZ, minY,
                        nx, seconds, drawTop, waveScale);
            }
            nIdx = neighborAcrossCached(boxes, i, Face.WEST);
            if (nIdx < 0) {
                quads += wallStrip(vc, m, toCamera, r, g, b, a * 0.8F,
                        false, minZ, maxZ, minX + WATER_INSET, minX, minY,
                        nz, seconds, drawTop, waveScale);
            }
            nIdx = neighborAcrossCached(boxes, i, Face.EAST);
            if (nIdx < 0) {
                quads += wallStrip(vc, m, toCamera, r, g, b, a * 0.8F,
                        false, minZ, maxZ, maxX - WATER_INSET, maxX, minY,
                        nz, seconds, drawTop, waveScale);
            }
            boxesDrawn++;
        }
        // 破口视觉：空气侧只在涨水时喷水花；水下气泡在灌满后仍然持续冒出。
        if (data.breaches().length >= 8) {
            float carryX = 0.0F;
            float carryY = 0.0F;
            float carryZ = 0.0F;
            if (ship != null) {
                Vector3dc velocity = ship.getVelocity();
                carryX = (float) (velocity.x() / 20.0D);
                carryY = (float) (velocity.y() / 20.0D);
                carryZ = (float) (velocity.z() / 20.0D);
            }
            spawnBreachWater(level, toCamera, cameraPos, data.breaches(), data.boxes(), boxes,
                    carryX, carryY, carryZ);
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
                WATER_BUFFERS.endBatch(type);
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

    // ------------------------------------------------------------------ 破口进水视觉

    /**
     * 破口进水视觉，按破口与水面的关系分两种：
     *
     * <ul>
     *   <li><b>破口在水面以上（舱内还是空气）</b>：水柱喷进空气会碎成水花/水雾，
     *       用原版 {@code SPLASH} + {@code CLOUD} 从洞口朝舱内、向下喷；</li>
     *   <li><b>破口在水面以下（舱室已经灌满/半灌满）</b>：水从洞口挤出气泡，
     *       用放大版 {@link ShipBubbleParticle} 上浮。</li>
     * </ul>
     *
     * <p>两种都只在“水面还在涨”时生成；每次总量有上限，避免破口很多时粒子爆量。</p>
     */
    private static void spawnBreachWater(ClientLevel level, Matrix4d toCamera, Vec3 cameraPos,
                                         float[] breaches, float[] target, float[] boxes,
                                         float carryX, float carryY, float carryZ) {
        if (level == null || breaches.length < 8) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastBreachParticleNanos < 100_000_000L) {
            return;
        }
        lastBreachParticleNanos = now;
        // 破口很多时（整片船壳被炸开）必须限流：每 100ms 总共 72 个粒子，
        // 气泡另有存活上限。否则 80+ 个洞 × 长寿命气泡会堆到几万个，直接把内存吃光。
        int holeCount = breaches.length / 8;
        if (holeCount <= 0) {
            return;
        }
        int budget = 72;
        int start = Math.floorMod(breachRoundRobin++, holeCount);
        for (int n = 0; n < holeCount && budget > 0; n++) {
            int i = Math.floorMod(start + n, holeCount) * 8;
            float bx = breaches[i];
            float by = breaches[i + 1];
            float bz = breaches[i + 2];
            // 服务端直接告诉我们这个破口属于 boxes 里的哪一个空腔，避免 bbox 重叠时认错。
            int box = Math.round(breaches[i + 3]) * 7;
            if (box < 0 || box + 6 >= boxes.length || box + 6 >= target.length) {
                continue;
            }
            // 空腔的完整上下界（不是单个棱柱的），用来判断顶部破口/是否灌满。
            float ownerMinY = breaches[i + 6];
            float ownerMaxY = breaches[i + 7];
            // 空气侧只在“这个舱的水面还在涨”时喷水花；破口在水下时，即使舱室已经灌满、
            // 水面不再上涨，也要持续冒气泡（这正是原版“满水房间从破口冒泡”的效果）。
            float rise = target[box + 6] - boxes[box + 6];
            float surface = boxes[box + 6];
            boolean airSide = by > surface + 0.1F;
            if (airSide && rise <= 0.0005F) {
                continue;
            }
            // 水下气泡给一个最低强度，灌满后不会因为 rise=0 直接消失。
            float intensity = Math.min(1.0F, Math.max(0.20F, rise * 300.0F));
            // 服务端算好的“朝密封舱室质心”的方向；为 0 时退回空腔 bbox 中心。
            float dirX = breaches[i + 4];
            float dirZ = breaches[i + 5];
            if (dirX == 0.0F && dirZ == 0.0F) {
                float centerX = (boxes[box] + boxes[box + 3] + 1.0F) * 0.5F;
                float centerZ = (boxes[box + 2] + boxes[box + 5] + 1.0F) * 0.5F;
                dirX = centerX - bx;
                dirZ = centerZ - bz;
                float length = (float) Math.sqrt(dirX * dirX + dirZ * dirZ);
                if (length > 1.0E-4F) {
                    dirX /= length;
                    dirZ /= length;
                } else {
                    dirX = 0.0F;
                    dirZ = 0.0F;
                }
            }
            WATER_SCRATCH.set(bx, by, bz);
            toCamera.transformPosition(WATER_SCRATCH);
            double wx = cameraPos.x + WATER_SCRATCH.x;
            double wy = cameraPos.y + WATER_SCRATCH.y;
            double wz = cameraPos.z + WATER_SCRATCH.z;
            // 粒子不能从破口格"中心"喷：0% 破口方块仍然渲染成实心，中心生成的
            // 粒子整片被自己的方块挡住（issue #14）。贴到洞口所在的面上——
            // 即未被实心邻居盖住、且最朝向相机的那一面。
            BlockPos breachCell = BlockPos.containing(bx, by, bz);
            Matrix4d breachToWorld = new Matrix4d()
                    .translation(cameraPos.x, cameraPos.y, cameraPos.z)
                    .mul(toCamera);
            Direction holeFace = ShipCellFaces.pickVisibleFace(level, breachCell, breachToWorld, cameraPos);
            double sx = wx;
            double sy = wy;
            double sz = wz;
            if (holeFace != null) {
                Vector3d faceCenter = breachToWorld.transformPosition(new Vector3d(
                        breachCell.getX() + 0.5D + holeFace.getStepX() * 0.5D,
                        breachCell.getY() + 0.5D + holeFace.getStepY() * 0.5D,
                        breachCell.getZ() + 0.5D + holeFace.getStepZ() * 0.5D), new Vector3d());
                sx = faceCenter.x + holeFace.getStepX() * 0.05D;
                sy = faceCenter.y + holeFace.getStepY() * 0.05D;
                sz = faceCenter.z + holeFace.getStepZ() * 0.05D;
            }
            float worldSurface = (float) (wy + (surface - by));
            // 进水方向 = 洞口朝舱内的水平方向；粒子主轴沿它，再加一个垂直方向的散射锥。
            float perpX = -dirZ;
            float perpZ = dirX;
            if (airSide) {
                // 空气侧：白色烟雾沿进水方向喷进舱内，向下落，带小角度散射。
                int perHole = 1 + (int) (intensity * 2.0F);
                for (int c = 0; c < perHole && budget-- > 0; c++) {
                    double side = (level.random.nextDouble() - 0.5D) * 0.16D;
                    double forward = 0.22D + level.random.nextDouble() * 0.16D;
                    level.addParticle(ParticleTypes.CLOUD,
                            sx + (level.random.nextDouble() - 0.5D) * 0.30D,
                            sy + (level.random.nextDouble() - 0.5D) * 0.24D,
                            sz + (level.random.nextDouble() - 0.5D) * 0.30D,
                            carryX + dirX * forward + perpX * side
                                    + (level.random.nextDouble() - 0.5D) * 0.04D,
                            carryY - 0.10D - level.random.nextDouble() * 0.14D,
                            carryZ + dirZ * forward + perpZ * side
                                    + (level.random.nextDouble() - 0.5D) * 0.04D);
                }
            } else {
                if (ShipBubbleParticle.liveCount() >= 260) {
                    continue;
                }
                // 水下：气泡朝舱内。满水舱降低密度；顶部破口用较大的向下初速度压进船里。
                boolean full = surface >= ownerMaxY + 1.0F - 0.01F;
                if (full) {
                    fullBubbleToggle = !fullBubbleToggle;
                    if (!fullBubbleToggle) {
                        continue;
                    }
                }
                boolean highBreach = by > (ownerMinY + ownerMaxY) * 0.5F + 0.5F;
                ShipBubbleParticle.setPendingSurface(worldSurface);
                ShipBubbleParticle.setPendingSink(highBreach);
                int perHole = full ? 1 : 1 + (int) (intensity * 2.0F);
                for (int c = 0; c < perHole && budget-- > 0; c++) {
                    double side = (level.random.nextDouble() - 0.5D) * 0.14D;
                    double forward = 0.18D + level.random.nextDouble() * 0.14D;
                    double vy = highBreach
                            ? -0.80D - level.random.nextDouble() * 0.40D
                            : 0.18D + level.random.nextDouble() * 0.28D;
                    level.addParticle(ModParticles.SHIP_BUBBLE.get(),
                            sx + (level.random.nextDouble() - 0.5D) * 0.30D,
                            sy + (level.random.nextDouble() - 0.5D) * 0.30D,
                            sz + (level.random.nextDouble() - 0.5D) * 0.30D,
                            dirX * forward + perpX * side
                                    + (level.random.nextDouble() - 0.5D) * 0.03D,
                            vy,
                            dirZ * forward + perpZ * side
                                    + (level.random.nextDouble() - 0.5D) * 0.03D);
                }
            }
        }
    }

    // ------------------------------------------------------------------ 帧率平滑

    /**
     * 帧间隔（秒），同时推进波浪相位。
     *
     * <p>上限 0.1s：切窗口/卡顿后不要一帧跨过很长一段波浪；水面平滑也用它，
     * 保证 10 Hz 的网络数据在 60/144/240 Hz 下都是连续上升。</p>
     */
    private static float frameDeltaSeconds() {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L
                ? 1.0F / 60.0F
                : (now - lastFrameNanos) / 1_000_000_000.0F;
        lastFrameNanos = now;
        dt = Math.max(0.0F, Math.min(0.1F, dt));
        waveSeconds += dt;
        return dt;
    }

    /**
     * 把服务端 2 Hz 的水面高度插值到每一帧。
     *
     * <p>只改客户端显示；{@link #isInsideDryZone} 仍然使用原始 packet，干湿/氧气判定
     * 不受视觉平滑影响。bbox 变了说明重扫过，直接对齐，不做跨舱动画。</p>
     */
    private static float[] smoothWater(ShipWaterStatePacket data, float dt) {
        float[] source = data.boxes();
        if (source.length < 7) {
            smoothedBoxes = source;
            smoothedShipId = data.shipId();
            smoothedPhysical = data.physical();
            return source;
        }
        if (smoothedBoxes.length != source.length
                || smoothedShipId != data.shipId()
                || smoothedPhysical != data.physical()) {
            smoothedBoxes = source.clone();
            smoothedShipId = data.shipId();
            smoothedPhysical = data.physical();
            return smoothedBoxes;
        }
        // 指数趋近：时间常数约 100ms，和 10 Hz 的同步步长对齐，水面连续而不是台阶。
        float alpha = 1.0F - (float) Math.exp(-dt * 10.0F);
        for (int i = 0; i + 6 < smoothedBoxes.length; i += 7) {
            boolean sameBox = smoothedBoxes[i] == source[i]
                    && smoothedBoxes[i + 1] == source[i + 1]
                    && smoothedBoxes[i + 2] == source[i + 2]
                    && smoothedBoxes[i + 3] == source[i + 3]
                    && smoothedBoxes[i + 4] == source[i + 4]
                    && smoothedBoxes[i + 5] == source[i + 5];
            smoothedBoxes[i] = source[i];
            smoothedBoxes[i + 1] = source[i + 1];
            smoothedBoxes[i + 2] = source[i + 2];
            smoothedBoxes[i + 3] = source[i + 3];
            smoothedBoxes[i + 4] = source[i + 4];
            smoothedBoxes[i + 5] = source[i + 5];
            float target = source[i + 6];
            float current = smoothedBoxes[i + 6];
            if (!sameBox || Math.abs(target - current) > 4.0F) {
                // 重扫/瞬移：直接对齐，避免水面慢慢爬过一大段。
                current = target;
            } else {
                current += (target - current) * alpha;
            }
            smoothedBoxes[i + 6] = current;
        }
        return smoothedBoxes;
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
        VertexConsumer productionVc = DIAG_BUFFERS.getBuffer(RenderType.debugFilledBox());
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
            DIAG_BUFFERS.endBatch(RenderType.debugFilledBox());
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
        if (RAW_QUAD_BUILDER.building()) {
            RAW_QUAD_BUILDER.discard();
        }
        BufferBuilder builder = RAW_QUAD_BUILDER;
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

    private enum Face {
        BOTTOM, NORTH, SOUTH, WEST, EAST
    }

    private static final int FACE_COUNT = 5;

    /**
     * 逐格水面表：用覆盖全部盒子的紧凑网格代替 {@code HashMap<Long,Float>}。
     *
     * <p>下标 = {@code (x - cellOriginX) * cellSpanZ + (z - cellOriginZ)}。数组跨帧复用，
     * 每帧只清空用到的那一段。原来是每帧新建两个 HashMap 并对每个格子做 {@code Long}
     * 装箱——一格一格的水面每帧上千次分配。</p>
     */
    private static void buildCellGrid(float[] boxes) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int i = 0; i + 6 < boxes.length; i += 7) {
            minX = Math.min(minX, Math.round(boxes[i]));
            maxX = Math.max(maxX, Math.round(boxes[i + 3]));
            minZ = Math.min(minZ, Math.round(boxes[i + 2]));
            maxZ = Math.max(maxZ, Math.round(boxes[i + 5]));
        }
        if (minX > maxX || minZ > maxZ) {
            // 没有可用盒子；把跨度设成 0，cornerAt 会全部走 fallback。
            cellOriginX = 0;
            cellOriginZ = 0;
            cellSpanX = 0;
            cellSpanZ = 0;
            return;
        }
        cellOriginX = minX;
        cellOriginZ = minZ;
        cellSpanX = maxX - minX + 1;
        cellSpanZ = maxZ - minZ + 1;
        int size = cellSpanX * cellSpanZ;
        if (cellTopGrid.length < size) {
            // 留一点余量：船略变大时不必每帧重新分配。
            int capacity = Math.max(size, cellTopGrid.length * 2);
            cellTopGrid = new float[capacity];
            cellScaleGrid = new float[capacity];
            cellPresentGrid = new boolean[capacity];
        } else {
            java.util.Arrays.fill(cellPresentGrid, 0, size, false);
        }
    }

    private static void putCellTop(int x, int z, float top, float scale) {
        int ix = x - cellOriginX;
        int iz = z - cellOriginZ;
        if (ix < 0 || ix >= cellSpanX || iz < 0 || iz >= cellSpanZ) {
            return;
        }
        int index = ix * cellSpanZ + iz;
        if (index >= cellPresentGrid.length) {
            return;
        }
        if (!cellPresentGrid[index] || top > cellTopGrid[index]) {
            cellPresentGrid[index] = true;
            cellTopGrid[index] = top;
            cellScaleGrid[index] = scale;
        }
    }

    /**
     * 重建邻居候选表。只在**几何**变化时做；水位变化不影响"谁挨着谁"。
     *
     * <p>每个 box 的 5 个面各有一个候选下标池。建表时套用所有只依赖几何的条件
     * （面相邻、XZ 重叠、邻居不整体悬在头顶）；每帧只对少数候选检查"邻居有没有水"
     * 并取最高水面。</p>
     */
    private static void ensureNeighborTable(float[] boxes) {
        int count = boxes.length / 7;
        long hash = geometryHash(boxes);
        if (hash == neighborGeometryHash && count == neighborBoxCount) {
            return;
        }
        neighborGeometryHash = hash;
        neighborBoxCount = count;
        int slots = count * FACE_COUNT;
        // 两个数组必须保持**同长**：下面 Arrays.fill(neighborCount, 0, slots, 0)
        // 和 neighborStart[slots] 都要求 length >= slots + 1，分开按不同公式扩容会让
        // 较慢的一边在某个 slots 上不够用而越界。这里一次分配同容量。
        if (neighborStart.length < slots + 1 || neighborCount.length < slots + 1) {
            int capacity = Math.max(slots + 1,
                    Math.max(neighborStart.length, neighborCount.length) * 2);
            neighborStart = new int[capacity];
            neighborCount = new int[capacity];
        }
        // 先统计每个槽位的候选数，前缀和得到池内起始位置，再填池——两趟，零装箱。
        java.util.Arrays.fill(neighborCount, 0, slots, 0);
        for (int i = 0; i < count; i++) {
            int base = i * 7;
            for (int f = 0; f < FACE_COUNT; f++) {
                for (int j = 0; j < count; j++) {
                    if (j == i) {
                        continue;
                    }
                    if (isGeometricNeighbor(boxes, base, j * 7, f)) {
                        neighborCount[i * FACE_COUNT + f]++;
                    }
                }
            }
        }
        int total = 0;
        for (int s = 0; s < slots; s++) {
            neighborStart[s] = total;
            total += neighborCount[s];
        }
        neighborStart[slots] = total;
        if (neighborPool.length < total) {
            neighborPool = new int[Math.max(total, neighborPool.length * 2)];
        }
        // 用一个游标数组回填池。
        if (fillCursor.length < slots) {
            fillCursor = new int[Math.max(slots, fillCursor.length * 2)];
        }
        for (int s = 0; s < slots; s++) {
            fillCursor[s] = neighborStart[s];
        }
        for (int i = 0; i < count; i++) {
            int base = i * 7;
            for (int f = 0; f < FACE_COUNT; f++) {
                int slot = i * FACE_COUNT + f;
                for (int j = 0; j < count; j++) {
                    if (j == i) {
                        continue;
                    }
                    if (isGeometricNeighbor(boxes, base, j * 7, f)) {
                        neighborPool[fillCursor[slot]++] = j * 7;
                    }
                }
            }
        }
    }

    private static int[] fillCursor = new int[0];

    /**
     * 只有几何条件的相邻判定（和原 {@code neighborAcross}/{@code hasNeighborBelow}
     * 里与水位无关的部分一致）。
     *
     * <p>NORTH 分支用 {@code jMaxZ} 与 {@code iMinZ} 比较：邻居的 maxZ 贴着自己的 minZ。
     * 旧实现误写成 {@code boxes[j+3]}（maxX），拿 X 去和 Z 比，条件几乎永远不成立，
     * 每根水柱的北面都被判成临空、多画一条竖面（"一格一条线"）。该 bug 已在 #12
     * 于旧函数上修过，这里迁移时同步带上。</p>
     *
     * @param face {@link Face} 的下标：0=BOTTOM 1=NORTH 2=SOUTH 3=WEST 4=EAST
     */
    private static boolean isGeometricNeighbor(float[] boxes, int ib, int jb, int face) {
        float iMinX = boxes[ib];
        float iMinY = boxes[ib + 1];
        float iMinZ = boxes[ib + 2];
        float iMaxX = boxes[ib + 3] + 1.0F;
        float iMaxY = boxes[ib + 4] + 1.0F;
        float iMaxZ = boxes[ib + 5] + 1.0F;
        float jMinX = boxes[jb];
        float jMinY = boxes[jb + 1];
        float jMinZ = boxes[jb + 2];
        float jMaxX = boxes[jb + 3] + 1.0F;
        float jMaxZ = boxes[jb + 5] + 1.0F;
        switch (face) {
            case 1: // NORTH：邻居的 maxZ 贴着我的 minZ
                return Math.abs(jMaxZ - iMinZ) < 1.0E-3F && jMinY < iMaxY
                        && overlap(iMinX, iMaxX, jMinX, jMaxX);
            case 2: // SOUTH
                return Math.abs(jMinZ - iMaxZ) < 1.0E-3F && jMinY < iMaxY
                        && overlap(iMinX, iMaxX, jMinX, jMaxX);
            case 3: // WEST
                return Math.abs(jMaxX - iMinX) < 1.0E-3F && jMinY < iMaxY
                        && overlap(iMinZ, iMaxZ, jMinZ, jMaxZ);
            case 4: // EAST
                return Math.abs(jMinX - iMaxX) < 1.0E-3F && jMinY < iMaxY
                        && overlap(iMinZ, iMaxZ, jMinZ, jMaxZ);
            default: // BOTTOM：邻居顶面正好贴着我的底面
                return Math.abs((boxes[jb + 4] + 1.0F) - iMinY) < 1.0E-3F
                        && overlap(iMinX, iMaxX, jMinX, jMaxX)
                        && overlap(iMinZ, iMaxZ, jMinZ, jMaxZ);
        }
    }

    /**
     * 只由几何决定的指纹（不含水面高度）。用一个简单的多项式滚过去，
     * 每次比较只花 O(boxes) 且不分配。
     */
    private static long geometryHash(float[] boxes) {
        long hash = 1125899906842597L;
        for (int i = 0; i + 6 < boxes.length; i += 7) {
            for (int k = 0; k < 6; k++) {
                hash = hash * 31L + Float.floatToIntBits(boxes[i + k]);
            }
        }
        return hash;
    }

    /**
     * 缓存版 {@code neighborAcross}：语义与原实现完全一致，只是候选集合预先算好。
     */
    private static int neighborAcrossCached(float[] boxes, int index, Face face) {
        int step = index / 7;
        int slot = step * FACE_COUNT + face.ordinal();
        if (slot < 0 || slot + 1 >= neighborStart.length) {
            return -1;
        }
        int from = neighborStart[slot];
        int to = neighborStart[slot + 1];
        if (from < 0 || to > neighborPool.length) {
            return -1;
        }
        float iMaxY = boxes[index + 4] + 1.0F;
        int best = -1;
        float bestTop = Float.NEGATIVE_INFINITY;
        for (int c = from; c < to; c++) {
            int j = neighborPool[c];
            // 动态条件：邻居自己必须真的有水。
            if (boxes[j + 6] <= boxes[j + 1] + 0.01F) {
                continue;
            }
            if (boxes[j + 1] >= iMaxY) {
                continue;
            }
            float jTop = Math.min(boxes[j + 6], boxes[j + 4] + 1.0F);
            if (jTop > bestTop) {
                bestTop = jTop;
                best = j;
            }
        }
        return best;
    }

    /** 缓存版 {@code hasNeighborBelow}。 */
    private static boolean hasNeighborBelowCached(float[] boxes, int index) {
        int step = index / 7;
        int slot = step * FACE_COUNT + Face.BOTTOM.ordinal();
        if (slot < 0 || slot + 1 >= neighborStart.length) {
            return false;
        }
        int from = neighborStart[slot];
        int to = neighborStart[slot + 1];
        if (from < 0 || to > neighborPool.length) {
            return false;
        }
        for (int c = from; c < to; c++) {
            int j = neighborPool[c];
            if (boxes[j + 6] > boxes[j + 1] + 0.01F) {
                return true;
            }
        }
        return false;
    }

    /** 这个盒子的水面画在哪（满舱时压到天花板内侧 1cm）。 */
    private static float boxDrawTop(float[] boxes, int index) {
        float maxY = boxes[index + 4] + 1.0F;
        float drawTop = Math.min(boxes[index + 6], maxY);
        if (drawTop >= maxY - 0.01F) {
            drawTop -= WATER_INSET;
        }
        return drawTop;
    }

    /** 这个盒子的波浪幅度系数（贴天花板时压平）。 */
    private static float boxWaveScale(float[] boxes, int index) {
        float maxY = boxes[index + 4] + 1.0F;
        float drawTop = boxDrawTop(boxes, index);
        return Math.max(0.0F, Math.min(1.0F, (maxY - 0.06F - drawTop) / 0.08F));
    }

    /**
     * 贴舱壁/临空一侧的水带：沿墙方向占满整格，只沿法线缩 1cm 防和方块面 z-fight。
     *
     * <p>顶边角点用与顶面完全相同的"角点平均高度 + 同相位波浪"求值（沿墙方向的
     * 坐标取棱柱边界，不取缩进后的位置），因此和顶面边缘严丝合缝；底边在
     * minY+1cm，与底面边缘重合。</p>
     */
    private static int wallStrip(VertexConsumer vc, Matrix4f m, Matrix4d toCamera,
                                 float red, float green, float blue, float alpha,
                                 boolean alongX, float a0, float a1,
                                 float drawPlane, float evalPlane, float minY,
                                 int segments, float seconds,
                                 float fallbackTop, float fallbackScale) {
        float bottom = minY + WATER_INSET;
        int drawn = 0;
        for (int k = 0; k < segments; k++) {
            float t0 = (float) k / segments;
            float t1 = (float) (k + 1) / segments;
            float p0 = a0 + (a1 - a0) * t0;
            float p1 = a0 + (a1 - a0) * t1;
            float x0;
            float z0;
            float x1;
            float z1;
            float ex0;
            float ez0;
            float ex1;
            float ez1;
            if (alongX) {
                x0 = p0; z0 = drawPlane; x1 = p1; z1 = drawPlane;
                ex0 = p0; ez0 = evalPlane; ex1 = p1; ez1 = evalPlane;
            } else {
                x0 = drawPlane; z0 = p0; x1 = drawPlane; z1 = p1;
                ex0 = evalPlane; ez0 = p0; ex1 = evalPlane; ez1 = p1;
            }
            float[] c0 = cornerAt(ex0, ez0, fallbackTop, fallbackScale, CORNER_A);
            float[] c1 = cornerAt(ex1, ez1, fallbackTop, fallbackScale, CORNER_B);
            float top0 = c0[0] + wave(seconds, ex0, ez0) * c0[1];
            float top1 = c1[0] + wave(seconds, ex1, ez1) * c1[1];
            quadXf(vc, m, toCamera, red, green, blue, alpha,
                    x0, bottom, z0,
                    x0, top0, z0,
                    x1, top1, z1,
                    x1, bottom, z1);
            drawn++;
        }
        return drawn;
    }

    /**
     * 角点 (cx, cz) 周围最多四格的水面平均高度和平均波浪系数。
     *
     * <p>相邻 quad 的共享角点用同一组格子求平均，结果完全相等——这是水面
     * 在相邻水柱水位不同时仍然连续、不出台阶/分界线的关键。干燥格不参与。</p>
     *
     * <p>结果写进调用方提供的 {@code out}，不再每次 new 数组（水面网格每帧上千次调用）。</p>
     */
    private static float[] cornerAt(float cx, float cz,
                                    float fallbackTop, float fallbackScale, float[] out) {
        int fx = (int) Math.floor(cx);
        int fz = (int) Math.floor(cz);
        float top = 0.0F;
        float scale = 0.0F;
        int count = 0;
        for (int dx = -1; dx <= 0; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                int ix = fx + dx - cellOriginX;
                int iz = fz + dz - cellOriginZ;
                if (ix < 0 || ix >= cellSpanX || iz < 0 || iz >= cellSpanZ) {
                    continue;
                }
                int index = ix * cellSpanZ + iz;
                if (index >= cellPresentGrid.length || !cellPresentGrid[index]) {
                    continue;
                }
                top += cellTopGrid[index];
                scale += cellScaleGrid[index];
                count++;
            }
        }
        if (count == 0) {
            out[0] = fallbackTop;
            out[1] = fallbackScale;
            return out;
        }
        out[0] = top / count;
        out[1] = scale / count;
        return out;
    }

    private static boolean overlap(float aMin, float aMax, float bMin, float bMax) {
        return Math.max(aMin, bMin) < Math.min(aMax, bMax) - 1.0E-3F;
    }

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
