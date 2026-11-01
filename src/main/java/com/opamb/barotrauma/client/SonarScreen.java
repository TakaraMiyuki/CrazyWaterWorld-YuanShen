package com.opamb.barotrauma.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.opamb.barotrauma.menu.SonarMenu;
import com.opamb.barotrauma.network.ModNetwork;
import com.opamb.barotrauma.network.ShipActionPacket;
import com.opamb.barotrauma.network.SonarPingPacket;
import com.opamb.barotrauma.sonar.ClientSonarCache;
import com.opamb.barotrauma.sonar.SonarScan;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

/**
 * 声纳 / 操舵台。
 *
 * <p>版式是"左边探测、右边操舵"：</p>
 * <ul>
 *   <li><b>左：圆形示波器</b>——不是表盘，没有刻度和数字；每发一次脉冲，亮点从圆心向外扩散，
 *       推过的地方才亮、随后慢慢暗下去，和真实声纳"等回声"一样。鼠标拖拽只转视角，不操舵。</li>
 *   <li><b>右：独立舵盘</b>——手动驾驶时是八向航向 + 推力 + 升降；
 *       自动驾驶时改成"在左边画面上点目标点"的说明 + 取消标记。</li>
 * </ul>
 *
 * <p><b>自动驾驶 = 前往声纳上标记的位置</b>：在画面上点一下，船朝那个世界坐标开过去，
 * 到点后停住保持。不是原地悬停。</p>
 */
public class SonarScreen extends AbstractContainerScreen<SonarMenu> {

    // ---- 版式 ----
    private static final int PANEL_W = 500;
    private static final int PANEL_H = 276;
    private static final int SCOPE_CX = 128;
    private static final int SCOPE_CY = 138;
    private static final int SCOPE_R = 106;
    private static final int FIELD_R = 78;

    private static final int COL_X = 242;
    private static final int COL_W = 250;

    // 控制盒的相对行高（相对各盒顶）
    private static final int SONAR_BOX_Y = 24;
    private static final int HELM_BOX_Y = 110;
    private static final int HELM_BOX_H = 126;

    /** 一次声纳脉冲由内向外扩散的周期（毫秒）。 */
    private static final float PING_MS = 2600.0F;

    // ---- 配色 ----
    private static final int PANEL_BG = 0xF01E2420;
    private static final int PANEL_EDGE = 0xFF3B463C;
    private static final int BOX_BG = 0xFF2A322C;
    private static final int BOX_EDGE = 0xFF4A554B;
    private static final int TEXT = 0xFFD8D2BF;
    private static final int DIM = 0xFF8B9184;
    private static final int ACCENT = 0xFFE0762F;
    private static final int LED = 0xFF7BE38B;
    private static final int SCOPE_BG = 0xFF05090A;
    private static final int SCOPE_RIM = 0xFF3A4238;
    private static final int SCOPE_TICK = 0xFF7E5B36;
    private static final int TARGET_COLOR = 0xFFFFB03A;
    private static final int BTN_BG = 0xFF232B25;
    private static final int BTN_ON = 0xFF3F5A40;

    private float camYaw;
    private float camPitch = 0.34F;

    private int range;
    private int pingCooldown;
    private long openedAt;

    private boolean orbiting;
    private boolean draggingZoom;
    private boolean synced;
    private double lastMouseX;
    private double lastMouseY;
    private double pressX;
    private double pressY;
    private boolean draggedFarEnough;

    private int headingX;
    private int headingY;
    private int headingZ;
    private int power = 100;
    private boolean autoMode;
    private SonarScan.Mode mode = SonarScan.Mode.ACTIVE;

    /** 本地先显示的标记点（相对玩家），服务端回报到达后以回报为准。 */
    private boolean localTarget;
    private int localTargetX;
    private int localTargetY;
    private int localTargetZ;

    public SonarScreen(SonarMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = PANEL_W;
        this.imageHeight = PANEL_H;
        this.range = menu.range();
    }

    @Override
    protected void init() {
        super.init();
        ClientSonarCache.clear();
        openedAt = System.currentTimeMillis();
        sendPing();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        pollKeys();
        if (--pingCooldown <= 0) {
            sendPing();
        }
    }

    private void sendPing() {
        double cp = Math.cos(camPitch);
        ModNetwork.send(new SonarPingPacket(menu.sonarPos(), range, mode.id(),
                (float) (Math.sin(camYaw) * cp), (float) Math.sin(camPitch), (float) (Math.cos(camYaw) * cp)));
        pingCooldown = 20;
    }

    // ------------------------------------------------------------------ 键盘（手动模式）

    private void pollKeys() {
        if (minecraft == null || minecraft.getWindow() == null) {
            return;
        }
        long window = minecraft.getWindow().getWindow();
        boolean forward = down(window, GLFW.GLFW_KEY_W) || down(window, GLFW.GLFW_KEY_UP);
        boolean back = down(window, GLFW.GLFW_KEY_S) || down(window, GLFW.GLFW_KEY_DOWN);
        boolean left = down(window, GLFW.GLFW_KEY_A) || down(window, GLFW.GLFW_KEY_LEFT);
        boolean right = down(window, GLFW.GLFW_KEY_D) || down(window, GLFW.GLFW_KEY_RIGHT);
        boolean up = down(window, GLFW.GLFW_KEY_SPACE);
        boolean sink = down(window, GLFW.GLFW_KEY_LEFT_SHIFT) || down(window, GLFW.GLFW_KEY_RIGHT_SHIFT);

        if (autoMode) {
            if (headingX != 0 || headingY != 0 || headingZ != 0) {
                headingX = headingY = headingZ = 0;
                sendHeading(0, 0, 0);
            }
            return;
        }

        float fwd = (forward ? 1 : 0) - (back ? 1 : 0);
        float strafe = (right ? 1 : 0) - (left ? 1 : 0);
        int dy = (up ? 1 : 0) - (sink ? 1 : 0);

        int dx;
        int dz;
        if (fwd == 0.0F && strafe == 0.0F) {
            dx = 0;
            dz = 0;
        } else {
            float sin = Mth.sin(camYaw);
            float cos = Mth.cos(camYaw);
            float wx = sin * fwd + (-cos) * strafe;
            float wz = cos * fwd + sin * strafe;
            dx = Math.abs(wx) > 0.38F ? (wx > 0 ? 1 : -1) : 0;
            dz = Math.abs(wz) > 0.38F ? (wz > 0 ? 1 : -1) : 0;
        }

        if (dx != headingX || dz != headingZ) {
            headingX = dx;
            headingZ = dz;
            sendHeading(dx, headingY, dz);
        }
        if (dy != headingY) {
            headingY = dy;
            sendHeading(headingX, dy, headingZ);
        }
    }

    private static boolean down(long window, int key) {
        return InputConstants.isKeyDown(window, key);
    }

    private void sendHeading(int x, int y, int z) {
        ModNetwork.send(new ShipActionPacket(menu.sonarPos(), ShipActionPacket.SET_HEADING, x, y, z));
    }

    @Override
    public void onClose() {
        if (!autoMode && (headingX != 0 || headingY != 0 || headingZ != 0)) {
            sendHeading(0, 0, 0);
        }
        super.onClose();
    }

    // ------------------------------------------------------------------ 背景

    @Override
    public void renderBackground(GuiGraphics graphics) {
        graphics.fill(0, 0, this.width, this.height, 0xB0020506);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x, y, x + PANEL_W, y + PANEL_H, PANEL_BG);
        graphics.fill(x, y, x + PANEL_W, y + 1, PANEL_EDGE);
        graphics.fill(x, y + PANEL_H - 1, x + PANEL_W, y + PANEL_H, PANEL_EDGE);
        graphics.fill(x, y, x + 1, y + PANEL_H, PANEL_EDGE);
        graphics.fill(x + PANEL_W - 1, y, x + PANEL_W, y + PANEL_H, PANEL_EDGE);
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!synced && ClientSonarCache.frame() != null) {
            power = ClientSonarCache.power();
            autoMode = ClientSonarCache.autopilot();
            headingX = ClientSonarCache.headingX();
            headingY = ClientSonarCache.headingY();
            headingZ = ClientSonarCache.headingZ();
            synced = true;
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        drawScope(graphics);
        drawSonarBox(graphics);
        drawHelmBox(graphics, mouseX, mouseY);
        drawFooter(graphics);
    }

    // ---------------------------------------------------------- 左：示波器

    private void drawScope(GuiGraphics graphics) {
        int cx = leftPos + SCOPE_CX;
        int cy = topPos + SCOPE_CY;

        disc(graphics, cx, cy, SCOPE_R, SCOPE_RIM);
        disc(graphics, cx, cy, SCOPE_R - 2, 0xFF121815);
        disc(graphics, cx, cy, FIELD_R + 2, 0xFF2C3329);
        disc(graphics, cx, cy, FIELD_R, SCOPE_BG);
        // 只有一圈量程参考线，不是表盘：没有刻度、没有数字
        ring(graphics, cx, cy, FIELD_R / 2, 0xFF16302A);

        SonarScan.Frame frame = ClientSonarCache.frame();
        int viewRange = frame == null ? range : frame.range();
        if (frame != null) {
            drawCloud(graphics, cx, cy, frame, viewRange);
        }
        if (mode != SonarScan.Mode.PASSIVE) {
            drawWavefront(graphics, cx, cy);
        }
        if (frame == null) {
            graphics.drawCenteredString(font, "扫描中…", cx, cy - 4, DIM);
        }

        drawHeadingArrow(graphics, cx, cy);
        drawTargetMarker(graphics, cx, cy, viewRange);
        drawBearings(graphics, cx, cy);
        graphics.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFD8FFE8);

        // 圆下方直接报航向：之前只有底部一行小字，转视角之后完全不知道船头朝哪
        String headingText = headingX == 0 && headingZ == 0
                ? (autoMode ? "自动·无航向" : "停车")
                : headingName(headingX, headingY, headingZ) + " " + String.format("%03d°",
                        bearingDegrees(headingX, headingZ));
        graphics.drawString(font, headingText, cx - font.width(headingText) / 2, cy + FIELD_R + 7,
                autoMode ? TARGET_COLOR : 0xFF9FE8C8, false);
    }

    /** 东南西北四个方位字，跟着视角转——转完也知道哪边是哪儿。 */
    private void drawBearings(GuiGraphics graphics, int cx, int cy) {
        float[] b = basis();
        float rx = b[3], rz = b[5], ux = b[6], uz = b[8];
        String[] names = {"北", "东", "南", "西"};
        int[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (int i = 0; i < 4; i++) {
            float sr = dirs[i][0] * rx + dirs[i][1] * rz;
            float su = dirs[i][0] * ux + dirs[i][1] * uz;
            double len = Math.sqrt((double) sr * sr + (double) su * su);
            if (len < 0.15D) {
                continue;
            }
            int px = cx + (int) Math.round(sr / len * (FIELD_R + 11));
            int py = cy - (int) Math.round(su / len * (FIELD_R + 11));
            graphics.drawString(font, names[i], px - font.width(names[i]) / 2, py - 4,
                    i == 0 ? ACCENT : 0xFF4E6A52, false);
        }
    }

    /** 相机基向量：前 / 右 / 上。right = forward × up，取反整个画面会左右镜像。 */
    private float[] basis() {
        float cp = Mth.cos(camPitch);
        float sp = Mth.sin(camPitch);
        float cosYaw = Mth.cos(camYaw);
        float sinYaw = Mth.sin(camYaw);
        return new float[]{
                sinYaw * cp, sp, cosYaw * cp,
                -cosYaw, 0.0F, sinYaw,
                -sp * sinYaw, cp, -sp * cosYaw
        };
    }

    private void drawCloud(GuiGraphics graphics, int cx, int cy, SonarScan.Frame frame, int viewRange) {
        float[] b = basis();
        float fx = b[0], fy = b[1], fz = b[2];
        float rx = b[3], rz = b[5];
        float ux = b[6], uy = b[7], uz = b[8];

        float camDistance = viewRange * 1.5F;
        float focal = FIELD_R * 0.95F / 0.57735F;
        float max = Math.max(1.0F, frame.range());
        int fieldSq = FIELD_R * FIELD_R;

        // 声纳是"发一炮、等回声"：亮点从圆心往外推，推过的地方才亮
        float wavefront = pingProgress();

        for (int point : frame.points()) {
            int dx = SonarScan.x(point);
            int dy = SonarScan.y(point);
            int dz = SonarScan.z(point);
            int type = SonarScan.type(point);

            float depth = dx * fx + dy * fy + dz * fz + camDistance;
            if (depth < 2.0F) {
                continue;
            }
            if (type == SonarScan.TYPE_TERRAIN && depth < camDistance) {
                continue;
            }
            int px = cx + Math.round((dx * rx + dz * rz) * focal / depth);
            int py = cy - Math.round((dx * ux + dy * uy + dz * uz) * focal / depth);
            int ox = px - cx;
            int oy = py - cy;
            if (ox * ox + oy * oy > fieldSq) {
                continue;
            }

            float reveal = 1.0F;
            if (type == SonarScan.TYPE_TERRAIN) {
                // 屏幕半径 = 这条回声走了多远
                float sr = (float) Math.sqrt((double) (ox * ox + oy * oy)) / FIELD_R;
                float age = wavefront - sr;
                if (age < 0.0F) {
                    // 这一轮波前还没推到这里：保留上一次探测的暗底，不要清屏。
                    // 清屏的话每次脉冲都黑一下，看起来像"重新探测"，实际是同一份数据在刷新。
                    reveal = 0.30F;
                } else {
                    // 波前刚推过最亮，之后慢慢回落到暗底
                    reveal = (float) (0.45D + 0.55D * Math.exp(-age * 1.8D));
                }
            }

            float distance = Mth.sqrt(dx * dx + dy * dy + dz * dz);
            float t = Mth.clamp(distance / max, 0.0F, 1.0F);
            // 点画大一点：1 像素在深色底上几乎看不见，糊成一片就没法判断形状
            int size = distance < max * 0.3F ? 3 : 2;
            graphics.fill(px, py, px + size, py + size, cloudColor(type, t, reveal));
        }
    }

    private static int cloudColor(int type, float t, float reveal) {
        if (type == SonarScan.TYPE_HULL) {
            return argb(210, (int) (60 + 60 * (1 - t)), (int) (170 + 80 * (1 - t)), (int) (130 + 60 * (1 - t)));
        }
        if (type == SonarScan.TYPE_CONTACT) {
            return argb(255, 255, 90, 74);
        }
        int alpha = (int) ((1.05F - 0.45F * t) * reveal * 255.0F);
        int r = (int) (30 + 80 * (1 - t));
        int g = (int) (225 - 90 * t);
        int b = (int) (232 - 20 * t);
        return argb(Mth.clamp(alpha, 0, 255), r, g, b);
    }

    /** 声纳脉冲的波前：从圆心往外推的几圈，越外越暗。 */
    private void drawWavefront(GuiGraphics graphics, int cx, int cy) {
        float wavefront = pingProgress();
        for (int trail = 3; trail >= 0; trail--) {
            float ratio = wavefront - trail * 0.035F;
            if (ratio <= 0.02F || ratio > 1.0F) {
                continue;
            }
            int alpha = trail == 0 ? 150 : 46 - trail * 11;
            if (alpha <= 0) {
                continue;
            }
            ring(graphics, cx, cy, Math.round(ratio * FIELD_R), argb(alpha, 120, 255, 200));
        }
    }

    /** 本次脉冲扩散到量程的百分之几（0..1）；打开界面就从圆心重新开始。 */
    private float pingProgress() {
        long elapsed = System.currentTimeMillis() - openedAt;
        if (elapsed < 0L) {
            return 0.0F;
        }
        return (float) (elapsed % (long) PING_MS) / PING_MS;
    }

    /** 圆心箭头：指向船实际在走的方向。 */
    private void drawHeadingArrow(GuiGraphics graphics, int cx, int cy) {
        if (headingX == 0 && headingY == 0 && headingZ == 0) {
            graphics.fill(cx - 6, cy - 6, cx - 4, cy + 6, DIM);
            graphics.fill(cx + 4, cy - 6, cx + 6, cy + 6, DIM);
            graphics.fill(cx - 6, cy - 6, cx + 6, cy - 4, DIM);
            graphics.fill(cx - 6, cy + 4, cx + 6, cy + 6, DIM);
            return;
        }
        float[] b = basis();
        float sxRight = headingX * b[3] + headingZ * b[5];
        float sxUp = headingX * b[6] + headingY * b[7] + headingZ * b[8];
        double len = Math.sqrt((double) sxRight * sxRight + (double) sxUp * sxUp);
        if (len < 0.15D) {
            int dir = headingY >= 0 ? -1 : 1;
            graphics.fill(cx - 5, cy + dir * 12, cx + 5, cy + dir * 14, ACCENT);
            graphics.fill(cx - 2, cy + dir * 16, cx + 2, cy + dir * 20, ACCENT);
            return;
        }
        double nx = sxRight / len;
        double ny = -sxUp / len;
        float reach = 26.0F + 30.0F * (power / 100.0F);
        int tipX = cx + (int) Math.round(nx * reach);
        int tipY = cy + (int) Math.round(ny * reach);
        line(graphics, cx, cy, tipX, tipY, ACCENT);
        line(graphics, cx, cy, tipX, tipY, 0xFFFFC08A);
        double px = -ny;
        double py = nx;
        line(graphics, tipX, tipY, tipX - (int) Math.round(nx * 7 + px * 5),
                tipY - (int) Math.round(ny * 7 + py * 5), ACCENT);
        line(graphics, tipX, tipY, tipX - (int) Math.round(nx * 7 - px * 5),
                tipY - (int) Math.round(ny * 7 - py * 5), ACCENT);
    }

    /** 自动驾驶目标点标记。 */
    private void drawTargetMarker(GuiGraphics graphics, int cx, int cy, int viewRange) {
        boolean has = ClientSonarCache.hasTarget() || localTarget;
        if (!has) {
            return;
        }
        int tx = ClientSonarCache.hasTarget() ? ClientSonarCache.targetX() : localTargetX;
        int ty = ClientSonarCache.hasTarget() ? ClientSonarCache.targetY() : localTargetY;
        int tz = ClientSonarCache.hasTarget() ? ClientSonarCache.targetZ() : localTargetZ;

        float[] b = basis();
        float camDistance = viewRange * 1.5F;
        float focal = FIELD_R * 0.95F / 0.57735F;
        float depth = tx * b[0] + ty * b[1] + tz * b[2] + camDistance;
        if (depth < 2.0F) {
            return;
        }
        int px = cx + Math.round((tx * b[3] + tz * b[5]) * focal / depth);
        int py = cy - Math.round((tx * b[6] + ty * b[7] + tz * b[8]) * focal / depth);
        int ox = px - cx;
        int oy = py - cy;
        if (ox * ox + oy * oy > FIELD_R * FIELD_R) {
            double len = Math.sqrt((double) ox * ox + (double) oy * oy);
            int ex = cx + (int) Math.round(ox / len * (FIELD_R - 6));
            int ey = cy + (int) Math.round(oy / len * (FIELD_R - 6));
            graphics.fill(ex - 3, ey - 3, ex + 4, ey + 4, TARGET_COLOR);
            return;
        }
        line(graphics, cx, cy, px, py, 0x66FFB03A);
        graphics.fill(px - 5, py - 5, px - 3, py + 6, TARGET_COLOR);
        graphics.fill(px + 4, py - 5, px + 6, py + 6, TARGET_COLOR);
        graphics.fill(px - 5, py - 5, px + 6, py - 3, TARGET_COLOR);
        graphics.fill(px - 5, py + 4, px + 6, py + 6, TARGET_COLOR);
    }

    // ---------------------------------------------------------- 右上：声纳设置

    private void drawSonarBox(GuiGraphics graphics) {
        int x = leftPos + COL_X;
        int y = topPos + SONAR_BOX_Y;
        box(graphics, x, y, COL_W, 80);

        radio(graphics, x + 8, y + 8, "被动声纳", mode == SonarScan.Mode.PASSIVE);
        radio(graphics, x + 8, y + 24, "主动声纳", mode != SonarScan.Mode.PASSIVE);
        switchRow(graphics, x + 8, y + 42, "定向脉冲 " + (int) SonarScan.CONE_DEGREES + "°",
                mode == SonarScan.Mode.DIRECTIONAL);

        graphics.drawString(font, "量程", x + 8, y + 58, TEXT, false);
        String zoom = range + " m";
        graphics.drawString(font, zoom, x + COL_W - 8 - font.width(zoom), y + 58, LED, false);
        int barX = x + 8;
        int barW = COL_W - 16;
        int barY = y + 69;
        graphics.fill(barX, barY, barX + barW, barY + 6, 0xFF141A16);
        int knob = barX + Math.round(barW * (range - SonarScan.MIN_RANGE)
                / (float) (SonarScan.MAX_RANGE - SonarScan.MIN_RANGE));
        graphics.fill(barX, barY, knob, barY + 6, 0xFF4E6A52);
        graphics.fill(knob - 2, barY - 2, knob + 2, barY + 8, 0xFFC8CBB4);
    }

    // ---------------------------------------------------------- 右下：舵盘

    private void drawHelmBox(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = leftPos + COL_X;
        int y = topPos + HELM_BOX_Y;
        box(graphics, x, y, COL_W, HELM_BOX_H);

        radio(graphics, x + 8, y + 8, "手动驾驶", !autoMode);
        radio(graphics, x + 8, y + 24, "自动驾驶", autoMode);
        graphics.fill(x + 8, y + 40, x + COL_W - 8, y + 41, BOX_EDGE);

        if (autoMode) {
            drawAutoPanel(graphics, x, y, mouseX, mouseY);
        } else {
            drawManualPanel(graphics, x, y, mouseX, mouseY);
        }

        // 推力条（两种模式共用）
        graphics.drawString(font, "推力", x + 8, y + 96, DIM, false);
        int barX = x + 42;
        int barW = COL_W - 92;
        int barY = y + 99;
        graphics.fill(barX, barY, barX + barW, barY + 6, 0xFF141A16);
        int filled = barX + Math.round(barW * power / 100.0F);
        graphics.fill(barX, barY, filled, barY + 6, 0xFF1FD3B8);
        graphics.fill(barX, barY, barX + barW, barY + 6, 0x00000000);
        String pct = power + "%";
        graphics.drawString(font, pct, x + COL_W - 8 - font.width(pct), y + 96, LED, false);
    }

    private void drawManualPanel(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        String[] labels = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};
        int[][] dirs = {{0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}};
        for (int i = 0; i < 8; i++) {
            int bx = x + 8 + i * 30;
            int by = y + 46;
            boolean on = headingX == dirs[i][0] && headingZ == dirs[i][1];
            boolean hover = mouseX >= bx && mouseX < bx + 28 && mouseY >= by && mouseY < by + 16;
            graphics.fill(bx, by, bx + 28, by + 16, on ? BTN_ON : hover ? 0xFF39483A : BTN_BG);
            graphics.fill(bx, by, bx + 28, by + 1, BOX_EDGE);
            graphics.drawCenteredString(font, labels[i], bx + 14, by + 4, on ? 0xFFE8FFE8 : TEXT);
        }
        String[] vert = {"上浮", "停车", "下潜"};
        int[] values = {1, 0, -1};
        for (int i = 0; i < 3; i++) {
            int bx = x + 8 + i * 80;
            int by = y + 66;
            boolean on = values[i] == 0
                    ? (headingX == 0 && headingY == 0 && headingZ == 0)
                    : headingY == values[i];
            boolean hover = mouseX >= bx && mouseX < bx + 78 && mouseY >= by && mouseY < by + 16;
            graphics.fill(bx, by, bx + 78, by + 16, on ? BTN_ON : hover ? 0xFF39483A : BTN_BG);
            graphics.fill(bx, by, bx + 78, by + 1, BOX_EDGE);
            graphics.drawCenteredString(font, vert[i], bx + 39, by + 4, on ? 0xFFE8FFE8 : TEXT);
        }
        graphics.drawString(font, "也支持 WASD / 空格 / Shift", x + 8, y + 86, 0xFF5F6A5C, false);
    }

    private void drawAutoPanel(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        boolean has = ClientSonarCache.hasTarget() || localTarget;
        String line1;
        if (!has) {
            line1 = "目标：未标记";
        } else {
            int tx = ClientSonarCache.hasTarget() ? ClientSonarCache.targetX() : localTargetX;
            int ty = ClientSonarCache.hasTarget() ? ClientSonarCache.targetY() : localTargetY;
            int tz = ClientSonarCache.hasTarget() ? ClientSonarCache.targetZ() : localTargetZ;
            double dist = Math.sqrt((double) tx * tx + (double) ty * ty + (double) tz * tz);
            line1 = String.format("目标 %s %03d°  %.0f m", headingName(tx, ty, tz), bearingDegrees(tx, tz), dist);
        }
        graphics.drawString(font, line1, x + 8, y + 48, has ? TARGET_COLOR : DIM, false);
        graphics.drawString(font, has ? "单击左侧画面可改标记" : "单击左侧画面设置目标",
                x + 8, y + 60, 0xFF5F6A5C, false);

        int bx = x + 8;
        int by = y + 72;
        boolean hover = mouseX >= bx && mouseX < bx + 120 && mouseY >= by && mouseY < by + 16;
        graphics.fill(bx, by, bx + 120, by + 16, hover ? 0xFF5A3A2A : 0xFF3A2A22);
        graphics.fill(bx, by, bx + 120, by + 1, BOX_EDGE);
        graphics.drawCenteredString(font, "取消标记", bx + 60, by + 4, TEXT);
    }

    // ---------------------------------------------------------- 底部状态

    private void drawFooter(GuiGraphics graphics) {
        graphics.drawString(font, "声纳 / 操舵台", leftPos + 10, topPos + 6, ACCENT, false);
        String hint = "拖拽转视角 · 滚轮调速 · 右侧舵盘独立";
        graphics.drawString(font, hint, leftPos + PANEL_W - 12 - font.width(hint), topPos + 6,
                0xFF5F6A5C, false);

        int y = topPos + 244;
        String heading = headingX == 0 && headingZ == 0 && headingY == 0
                ? (autoMode ? "自动" : "停车") : headingName(headingX, headingY, headingZ);
        String line1 = String.format("航向 %s %03d°   深度 %d 米   速度 %.1f 公里/时   下潜 %.1f 公里/时",
                heading, bearingDegrees(headingX, headingZ), ClientSonarCache.depth(),
                ClientSonarCache.speedTenths() / 10.0D, ClientSonarCache.diveTenths() / 10.0D);
        graphics.drawString(font, line1, leftPos + 12, y, LED, false);

        String[] status = ClientSonarCache.status().split("\n");
        if (status.length > 0 && !status[0].isEmpty()) {
            int color = status[0].contains("引来生物") ? ACCENT : TEXT;
            graphics.drawString(font, status[0], leftPos + 12, y + 11, color, false);
        }
    }

    // ---------------------------------------------------------- 小工具

    private void box(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, BOX_BG);
        graphics.fill(x, y, x + w, y + 1, BOX_EDGE);
        graphics.fill(x, y + h - 1, x + w, y + h, BOX_EDGE);
        graphics.fill(x, y, x + 1, y + h, BOX_EDGE);
        graphics.fill(x + w - 1, y, x + w, y + h, BOX_EDGE);
    }

    private void radio(GuiGraphics graphics, int x, int y, String label, boolean selected) {
        graphics.fill(x, y, x + 8, y + 8, 0xFF11160F);
        if (selected) {
            graphics.fill(x + 2, y + 2, x + 6, y + 6, ACCENT);
        }
        graphics.drawString(font, label, x + 13, y, selected ? TEXT : DIM, false);
    }

    private void switchRow(GuiGraphics graphics, int x, int y, String label, boolean on) {
        graphics.fill(x, y, x + 22, y + 10, 0xFF11160F);
        graphics.fill(x + (on ? 12 : 1), y + 1, x + (on ? 21 : 10), y + 9,
                on ? ACCENT : 0xFF5A6256);
        graphics.drawString(font, label, x + 28, y + 1, on ? TEXT : DIM, false);
    }

    private static void disc(GuiGraphics graphics, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int half = (int) Math.sqrt((double) r * r - (double) dy * dy);
            graphics.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
        }
    }

    /** 空心圆：声纳波前和量程参考线都用它（不是实心盘，看着才像示波器）。 */
    private static void ring(GuiGraphics graphics, int cx, int cy, int r, int color) {
        if (r <= 0) {
            return;
        }
        int steps = Math.max(32, r * 6);
        for (int i = 0; i < steps; i++) {
            double angle = Math.PI * 2.0D * i / steps;
            int x = cx + (int) Math.round(Math.cos(angle) * r);
            int y = cy + (int) Math.round(Math.sin(angle) * r);
            graphics.fill(x, y, x + 1, y + 1, color);
        }
    }

    private static void radial(GuiGraphics graphics, int cx, int cy, int r0, int r1, float degrees, int color) {
        double rad = Math.toRadians(degrees);
        double sx = Math.sin(rad);
        double cyv = -Math.cos(rad);
        for (int r = r0; r <= r1; r++) {
            int x = cx + (int) Math.round(sx * r);
            int y = cy + (int) Math.round(cyv * r);
            graphics.fill(x, y, x + 1, y + 1, color);
        }
    }

    private static void line(GuiGraphics graphics, int x0, int y0, int x1, int y1, int color) {
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        if (steps <= 0) {
            return;
        }
        for (int i = 0; i <= steps; i++) {
            int x = x0 + (x1 - x0) * i / steps;
            int y = y0 + (y1 - y0) * i / steps;
            graphics.fill(x, y, x + 1, y + 1, color);
        }
    }

    private static int argb(int a, int r, int g, int b) {
        return ((a & 0xFF) << 24) | ((Mth.clamp(r, 0, 255) & 0xFF) << 16)
                | ((Mth.clamp(g, 0, 255) & 0xFF) << 8) | (Mth.clamp(b, 0, 255) & 0xFF);
    }

    private static int bearingDegrees(int dx, int dz) {
        if (dx == 0 && dz == 0) {
            return 0;
        }
        int deg = (int) Math.round(Math.toDegrees(Math.atan2(dx, -dz)));
        return (deg + 360) % 360;
    }

    private static String headingName(int dx, int dy, int dz) {
        String base;
        if (dz < 0) {
            base = dx == 0 ? "北" : dx > 0 ? "东北" : "西北";
        } else if (dz > 0) {
            base = dx == 0 ? "南" : dx > 0 ? "东南" : "西南";
        } else {
            base = dx > 0 ? "东" : "西";
        }
        if (dy > 0) {
            return base + "·上浮";
        }
        if (dy < 0) {
            return base + "·下潜";
        }
        return base;
    }

    // ---------------------------------------------------------- 交互

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int cx = leftPos + SCOPE_CX;
        int cy = topPos + SCOPE_CY;
        double ox = mouseX - cx;
        double oy = mouseY - cy;
        boolean inScope = ox * ox + oy * oy <= (double) SCOPE_R * SCOPE_R;

        if (button == 0 && inScope) {
            orbiting = true;
            pressX = mouseX;
            pressY = mouseY;
            draggedFarEnough = false;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        int x = leftPos + COL_X;
        int sy = topPos + SONAR_BOX_Y;
        if (inRect(mouseX, mouseY, x + 4, sy + 6, 130, 14)) {
            setMode(SonarScan.Mode.PASSIVE);
            return true;
        }
        if (inRect(mouseX, mouseY, x + 4, sy + 22, 130, 14)) {
            setMode(SonarScan.Mode.ACTIVE);
            return true;
        }
        if (inRect(mouseX, mouseY, x + 4, sy + 40, 160, 14)) {
            setMode(mode == SonarScan.Mode.DIRECTIONAL ? SonarScan.Mode.ACTIVE : SonarScan.Mode.DIRECTIONAL);
            return true;
        }
        if (inRect(mouseX, mouseY, x + 4, sy + 66, COL_W - 8, 14)) {
            draggingZoom = true;
            applyZoom(mouseX);
            return true;
        }

        int hy = topPos + HELM_BOX_Y;
        if (inRect(mouseX, mouseY, x + 4, hy + 6, 160, 14)) {
            setAuto(false);
            return true;
        }
        if (inRect(mouseX, mouseY, x + 4, hy + 22, 160, 14)) {
            setAuto(true);
            return true;
        }
        if (inRect(mouseX, mouseY, x + 40, hy + 94, COL_W - 50, 16)) {
            applyPower(mouseX, x);
            return true;
        }
        if (autoMode) {
            if (inRect(mouseX, mouseY, x + 4, hy + 68, 128, 22)) {
                clearTarget();
                return true;
            }
        } else {
            for (int i = 0; i < 8; i++) {
                if (inRect(mouseX, mouseY, x + 8 + i * 30, hy + 46, 28, 16)) {
                    int[][] dirs = {{0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}};
                    steer(dirs[i][0], headingY, dirs[i][1]);
                    return true;
                }
            }
            for (int i = 0; i < 3; i++) {
                if (inRect(mouseX, mouseY, x + 8 + i * 80, hy + 66, 78, 16)) {
                    int[] values = {1, 0, -1};
                    if (values[i] == 0) {
                        steer(0, 0, 0);
                    } else {
                        steer(headingX, values[i], headingZ);
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingZoom) {
            applyZoom(mouseX);
            return true;
        }
        if (orbiting) {
            camYaw -= (float) (mouseX - lastMouseX) * 0.008F;
            camPitch = Mth.clamp(camPitch - (float) (mouseY - lastMouseY) * 0.008F, -1.45F, 1.45F);
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            if (Math.abs(mouseX - pressX) + Math.abs(mouseY - pressY) > 6) {
                draggedFarEnough = true;
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        // 单击 = 标记目标，两种模式都生效（手动模式下点一下就切自动，省得先去点单选）
        if (orbiting && !draggedFarEnough && button == 0) {
            markTargetAt(mouseX, mouseY);
        }
        orbiting = false;
        draggingZoom = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int cx = leftPos + SCOPE_CX;
        int cy = topPos + SCOPE_CY;
        double ox = mouseX - cx;
        double oy = mouseY - cy;
        if (ox * ox + oy * oy <= (double) SCOPE_R * SCOPE_R) {
            int next = Mth.clamp(power + (delta > 0 ? 10 : -10), 10, 100);
            if (next != power) {
                power = next;
                ModNetwork.send(new ShipActionPacket(menu.sonarPos(), ShipActionPacket.SET_POWER, power));
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    /** 在示波器上点一下：沿视线找最近的声纳点当目标；没点中就按视线取半程距离。 */
    private void markTargetAt(double mouseX, double mouseY) {
        int cx = leftPos + SCOPE_CX;
        int cy = topPos + SCOPE_CY;
        float focal = FIELD_R * 0.95F / 0.57735F;

        float[] b = basis();
        float fx = b[0], fy = b[1], fz = b[2];
        float rx = b[3], ry = b[4], rz = b[5];
        float ux = b[6], uy = b[7], uz = b[8];

        float nx = (float) (mouseX - cx) / focal;
        float ny = (float) -(mouseY - cy) / focal;
        double dx = fx + rx * nx + ux * ny;
        double dy = fy + ry * nx + uy * ny;
        double dz = fz + rz * nx + uz * ny;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-4D) {
            return;
        }
        dx /= len;
        dy /= len;
        dz /= len;

        SonarScan.Frame frame = ClientSonarCache.frame();
        int viewRange = frame == null ? range : frame.range();
        // 相机在球外 camDistance 处，画点云时也是这么算的。射线必须从这里出发，
        // 从球心出发的话，点到射线的距离会系统性偏大一个 camDistance，点击位置整个跑偏。
        double camX = -fx * camDistanceOf(viewRange);
        double camY = -fy * camDistanceOf(viewRange);
        double camZ = -fz * camDistanceOf(viewRange);

        double bestDist = -1.0D;
        double bestOffset = 4.5D;
        if (frame != null) {
            for (int point : frame.points()) {
                if (SonarScan.type(point) != SonarScan.TYPE_TERRAIN) {
                    continue;
                }
                double px = SonarScan.x(point) - camX;
                double py = SonarScan.y(point) - camY;
                double pz = SonarScan.z(point) - camZ;
                double t = px * dx + py * dy + pz * dz;
                if (t <= 2.0D || t > viewRange * 2.5D) {
                    continue;
                }
                double qx = px - dx * t;
                double qy = py - dy * t;
                double qz = pz - dz * t;
                double off = Math.sqrt(qx * qx + qy * qy + qz * qz);
                if (off < bestOffset) {
                    bestOffset = off;
                    bestDist = t;
                }
            }
        }
        double dist = bestDist > 0.0D ? bestDist : Math.max(8.0D, viewRange * 0.5D);
        int tx = (int) Math.round(camX + dx * dist);
        int ty = (int) Math.round(camY + dy * dist);
        int tz = (int) Math.round(camZ + dz * dist);

        localTarget = true;
        localTargetX = tx;
        localTargetY = ty;
        localTargetZ = tz;
        autoMode = true;
        ModNetwork.send(new ShipActionPacket(menu.sonarPos(), ShipActionPacket.SET_AUTOPILOT_TARGET,
                tx, ty, tz));
        sendPing();
    }

    /** 相机到球心的距离，画点云和反投影必须用同一个值。 */
    private static float camDistanceOf(int viewRange) {
        return viewRange * 1.5F;
    }

    private void clearTarget() {
        localTarget = false;
        ModNetwork.send(new ShipActionPacket(menu.sonarPos(), ShipActionPacket.CLEAR_AUTOPILOT_TARGET));
        sendPing();
    }

    private void steer(int x, int y, int z) {
        if (x == headingX && y == headingY && z == headingZ) {
            return;
        }
        headingX = x;
        headingY = y;
        headingZ = z;
        sendHeading(x, y, z);
    }

    private void setAuto(boolean auto) {
        if (autoMode == auto) {
            return;
        }
        autoMode = auto;
        if (!auto) {
            headingX = headingY = headingZ = 0;
        }
        ModNetwork.send(new ShipActionPacket(menu.sonarPos(), ShipActionPacket.SET_AUTOPILOT, auto ? 1 : 0));
        sendPing();
    }

    private void setMode(SonarScan.Mode next) {
        if (mode == next) {
            return;
        }
        mode = next;
        sendPing();
    }

    private void applyPower(double mouseX, int colX) {
        int barX = colX + 42;
        int barW = COL_W - 92;
        float ratio = Mth.clamp((float) ((mouseX - barX) / (double) barW), 0.0F, 1.0F);
        int next = Mth.clamp(Math.round(ratio * 100.0F / 5.0F) * 5, 10, 100);
        if (next == power) {
            return;
        }
        power = next;
        ModNetwork.send(new ShipActionPacket(menu.sonarPos(), ShipActionPacket.SET_POWER, power));
    }

    private void applyZoom(double mouseX) {
        int barX = leftPos + COL_X + 8;
        int barW = COL_W - 16;
        float ratio = Mth.clamp((float) ((mouseX - barX) / (double) barW), 0.0F, 1.0F);
        int next = Math.round(SonarScan.MIN_RANGE + ratio * (SonarScan.MAX_RANGE - SonarScan.MIN_RANGE));
        next = Mth.clamp(Math.round(next / 8.0F) * 8, SonarScan.MIN_RANGE, SonarScan.MAX_RANGE);
        if (next != range) {
            range = next;
            sendPing();
        }
    }

    private static boolean inRect(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 文字统一在 render() 里画
    }
}
