package com.opamb.barotrauma.client;

import com.opamb.barotrauma.blockentity.DrainPumpBlockEntity;
import com.opamb.barotrauma.menu.PumpMenu;
import com.opamb.barotrauma.network.ModNetwork;
import com.opamb.barotrauma.network.PumpActionPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 排水泵界面：开关 + 四档排水量。状态来自 {@link PumpMenu} 的 ContainerData（自动同步），
 * 按钮只发 {@link PumpActionPacket}，服务端改完方块实体后由容器数据包刷新。
 */
public class PumpScreen extends AbstractContainerScreen<PumpMenu> {

    private static final int W = 260;
    private static final int H = 132;

    private static final int BG = 0xF01B2320;
    private static final int EDGE = 0xFF3B463C;
    private static final int BOX = 0xFF232B25;
    private static final int TEXT = 0xFFD8D2BF;
    private static final int DIM = 0xFF8B9184;
    private static final int ACCENT = 0xFFE0762F;
    private static final int GREEN = 0xFF7BE38B;
    private static final int HOVER = 0xFF39483A;

    public PumpScreen(PumpMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = W;
        this.imageHeight = H;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 标题用菜单的本地化名字，避免和原版标题叠成中英文双重影（和任务终端同一个问题）。
        graphics.drawString(font, title, 10, 7, ACCENT, false);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x, y, x + W, y + H, BG);
        graphics.fill(x, y, x + W, y + 1, EDGE);
        graphics.fill(x, y + H - 1, x + W, y + H, EDGE);
        graphics.fill(x, y, x + 1, y + H, EDGE);
        graphics.fill(x + W - 1, y, x + W, y + H, EDGE);

        boolean enabled = menu.data().get(PumpMenu.DATA_ENABLED) != 0;
        int rate = menu.data().get(PumpMenu.DATA_RATE);

        graphics.drawString(font, enabled ? "运行中" : "已关闭", x + W - 60, y + 7,
                enabled ? GREEN : DIM, false);

        graphics.drawString(font,
                String.format("排水量 %d 单位/秒（%.0f 格³/分）", rate, rate * 60.0D / 100.0D),
                x + 10, y + 24, TEXT, false);

        // 开关按钮
        int by = y + 42;
        boolean hover = mouseX >= x + 10 && mouseX < x + 110 && mouseY >= by && mouseY < by + 18;
        graphics.fill(x + 10, by, x + 110, by + 18, hover ? HOVER : BOX);
        graphics.fill(x + 10, by, x + 110, by + 1, EDGE);
        graphics.drawCenteredString(font, enabled ? "关闭排水" : "开启排水",
                x + 60, by + 5, TEXT);

        // 四档排水量
        graphics.drawString(font, "档位", x + 10, y + 70, DIM, false);
        int[] rates = DrainPumpBlockEntity.RATES;
        for (int i = 0; i < rates.length; i++) {
            int bx = x + 10 + i * 60;
            int byRate = y + 84;
            boolean on = rate == rates[i];
            boolean hoverRate = mouseX >= bx && mouseX < bx + 54 && mouseY >= byRate && mouseY < byRate + 18;
            graphics.fill(bx, byRate, bx + 54, byRate + 18,
                    on ? 0xFF3F5A40 : hoverRate ? HOVER : BOX);
            graphics.fill(bx, byRate, bx + 54, byRate + 1, EDGE);
            graphics.drawCenteredString(font, rates[i] / 100 + " 格/秒",
                    bx + 27, byRate + 5, on ? 0xFFE8FFE8 : TEXT);
        }
        graphics.drawString(font, "泵只能延缓进水；破口不堵，抽不赢海水。",
                x + 10, y + 110, DIM, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        int x = leftPos;
        int y = topPos;
        if (mouseX >= x + 10 && mouseX < x + 110 && mouseY >= y + 42 && mouseY < y + 60) {
            ModNetwork.CHANNEL.sendToServer(
                    new PumpActionPacket(menu.pumpPos(), PumpActionPacket.TOGGLE, 0));
            return true;
        }
        int[] rates = DrainPumpBlockEntity.RATES;
        for (int i = 0; i < rates.length; i++) {
            int bx = x + 10 + i * 60;
            int byRate = y + 84;
            if (mouseX >= bx && mouseX < bx + 54 && mouseY >= byRate && mouseY < byRate + 18) {
                ModNetwork.CHANNEL.sendToServer(
                        new PumpActionPacket(menu.pumpPos(), PumpActionPacket.SET_RATE, rates[i]));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
