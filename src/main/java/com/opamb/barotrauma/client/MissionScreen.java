package com.opamb.barotrauma.client;

import com.opamb.barotrauma.menu.MissionMenu;
import com.opamb.barotrauma.mission.Mission;
import com.opamb.barotrauma.network.MissionActionPacket;
import com.opamb.barotrauma.network.ModNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

/**
 * 任务终端界面：上半是任务板（接取/放弃/交付），下半是商店（修船/氧气瓶/焊枪/加固）。
 *
 * <p>状态来自 {@link ClientMissionCache}（服务端推送）；所有按钮只发
 * {@link MissionActionPacket}，成败由服务端判定并回包刷新。</p>
 */
public class MissionScreen extends AbstractContainerScreen<MissionMenu> {

    private static final int PANEL_W = 420;
    private static final int PANEL_H = 226;
    private static final int ROW_H = 26;

    private static final int BG = 0xF01B2320;
    private static final int EDGE = 0xFF3B463C;
    private static final int BOX = 0xFF232B25;
    private static final int TEXT = 0xFFD8D2BF;
    private static final int DIM = 0xFF8B9184;
    private static final int ACCENT = 0xFFE0762F;
    private static final int GREEN = 0xFF7BE38B;
    private static final int HOVER = 0xFF39483A;

    public MissionScreen(MissionMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = PANEL_W;
        this.imageHeight = PANEL_H;
    }

    @Override
    protected void init() {
        super.init();
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 标题用菜单的本地化名字（中文环境=任务终端，英文环境=Mission Terminal），
        // 不再在 renderBg 里手写中文，否则会和原版标题叠成中英文双重影（用户实测）。
        graphics.drawString(font, title, 10, 6, ACCENT, false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x, y, x + PANEL_W, y + PANEL_H, BG);
        graphics.fill(x, y, x + PANEL_W, y + 1, EDGE);
        graphics.fill(x, y + PANEL_H - 1, x + PANEL_W, y + PANEL_H, EDGE);
        graphics.fill(x, y, x + 1, y + PANEL_H, EDGE);
        graphics.fill(x + PANEL_W - 1, y, x + PANEL_W, y + PANEL_H, EDGE);

        graphics.drawString(font, "Credits：" + ClientMissionCache.credits(),
                x + PANEL_W - 90, y + 6, GREEN, false);

        List<Mission> offered = ClientMissionCache.offered();
        int rowY = y + 20;
        for (Mission mission : offered) {
            if (mission.state() == Mission.State.DONE) {
                continue;
            }
            drawMissionRow(graphics, x, rowY, mouseX, mouseY, mission);
            rowY += ROW_H + 2;
        }

        // 商店行
        int shopY = y + PANEL_H - 52;
        graphics.fill(x + 8, shopY - 4, x + PANEL_W - 8, shopY - 3, EDGE);
        graphics.drawString(font, "商店", x + 10, shopY, ACCENT, false);
        String[] labels = {"修船（1/HP）", "氧气瓶（40）", "焊枪（30）", "船体加固 +20%（150 起）"};
        int[] actions = {MissionActionPacket.BUY_REPAIR, MissionActionPacket.BUY_OXYGEN,
                MissionActionPacket.BUY_WELDER, MissionActionPacket.BUY_UPGRADE};
        for (int i = 0; i < labels.length; i++) {
            int bx = x + 8 + i * 102;
            boolean hover = mouseX >= bx && mouseX < bx + 100 && mouseY >= shopY + 12
                    && mouseY < shopY + 26;
            graphics.fill(bx, shopY + 12, bx + 100, shopY + 26, hover ? HOVER : BOX);
            graphics.fill(bx, shopY + 12, bx + 100, shopY + 13, EDGE);
            graphics.drawCenteredString(font, labels[i], bx + 50, shopY + 16, TEXT);
        }
        graphics.drawString(font, "修船/加固需要对着一艘已靠泊的船（24 格内）",
                x + 10, shopY + 30, DIM, false);
    }

    private void drawMissionRow(GuiGraphics graphics, int x, int y, int mouseX, int mouseY,
                                Mission mission) {
        graphics.fill(x + 8, y, x + PANEL_W - 8, y + ROW_H, BOX);
        graphics.fill(x + 8, y, x + PANEL_W - 8, y + 1, EDGE);

        String type = switch (mission.type) {
            case SALVAGE -> "打捞";
            case RECON -> "侦察";
            case DELIVERY -> "运输";
        };
        int dist = (int) Math.sqrt(minecraft.player.blockPosition().distSqr(mission.target));
        String title = String.format("#%d %s · 奖励 %d · 目标 %s %dm", mission.id, type,
                mission.reward, coord(mission), dist);
        graphics.drawString(font, title, x + 14, y + 4, TEXT, false);

        String stateLabel = switch (mission.state()) {
            case OFFERED -> "可接取";
            case ACTIVE -> "进行中";
            case READY -> "已达成！回终端交付";
            case DONE -> "已完成";
        };
        graphics.drawString(font, stateLabel, x + 14, y + 15,
                mission.state() == Mission.State.READY ? GREEN : DIM, false);

        boolean mine = ClientMissionCache.active() != null
                && ClientMissionCache.active().id == mission.id;
        String button = mine
                ? (mission.state() == Mission.State.READY ? "交付" : "放弃")
                : (mission.isTaken() ? "已被接走" : "接取");
        boolean enabled = mission.state() != Mission.State.DONE
                && (!mission.isTaken() || mine);
        int bx = x + PANEL_W - 78;
        boolean hover = enabled && mouseX >= bx && mouseX < bx + 62
                && mouseY >= y + 5 && mouseY < y + 21;
        graphics.fill(bx, y + 5, bx + 62, y + 21,
                !enabled ? 0xFF1A211C : hover ? HOVER : 0xFF2F3A2F);
        graphics.drawCenteredString(font, button, bx + 31, y + 9,
                enabled ? TEXT : 0xFF5A6256);
        if (enabled && hover) {
            int action = mine
                    ? (mission.state() == Mission.State.READY
                            ? MissionActionPacket.TURN_IN : MissionActionPacket.ABANDON)
                    : MissionActionPacket.ACCEPT;
            pendingAction = action;
            pendingMissionId = mission.id;
        } else if (hover) {
            pendingAction = 0;
        }
    }

    private int pendingAction;
    private int pendingMissionId = -1;

    private static String coord(Mission mission) {
        return mission.target.getX() + ", " + mission.target.getY() + ", " + mission.target.getZ();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && pendingAction != 0) {
            ModNetwork.CHANNEL.sendToServer(new MissionActionPacket(pendingAction, pendingMissionId));
            pendingAction = 0;
            return true;
        }
        // 商店按钮（无 missionId）
        int shopY = topPos + PANEL_H - 52;
        int localX = (int) mouseX - leftPos;
        int localY = (int) mouseY - topPos;
        if (button == 0 && localY >= shopY + 12 && localY < shopY + 26
                && localX >= 8 && localX < PANEL_W - 8) {
            int idx = (localX - 8) / 102;
            int[] actions = {MissionActionPacket.BUY_REPAIR, MissionActionPacket.BUY_OXYGEN,
                    MissionActionPacket.BUY_WELDER, MissionActionPacket.BUY_UPGRADE};
            if (idx >= 0 && idx < actions.length) {
                ModNetwork.CHANNEL.sendToServer(new MissionActionPacket(actions[idx]));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
