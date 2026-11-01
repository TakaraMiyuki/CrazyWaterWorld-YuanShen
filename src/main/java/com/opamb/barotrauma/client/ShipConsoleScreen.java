package com.opamb.barotrauma.client;

import com.opamb.barotrauma.menu.ShipConsoleMenu;
import com.opamb.barotrauma.network.ModNetwork;
import com.opamb.barotrauma.network.ShipActionPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 船控面板：按钮控制泵、反应堆、气闸、推进，底部显示打开时的实时数值。
 */
public class ShipConsoleScreen extends AbstractContainerScreen<ShipConsoleMenu> {

    private static final int PANEL_BG = 0xD0101820;
    private static final int ACCENT = 0xFF3FA6A0;
    private static final int TEXT = 0xFFB8E6E0;

    public ShipConsoleScreen(ShipConsoleMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 240;
        this.imageHeight = 190;
    }

    @Override
    protected void init() {
        super.init();
        int x = leftPos + 10;
        int y = topPos + 30;
        addRenderableWidget(button("\u6CF5\uFF1A\u62BD\u51FA", ShipActionPacket.PUMP_OUT, x, y));
        addRenderableWidget(button("\u6CF5\uFF1A\u6CE8\u5165", ShipActionPacket.PUMP_IN, x + 78, y));
        addRenderableWidget(button("\u53CD\u5E94\u5806 +10%", ShipActionPacket.REACTOR_UP, x, y + 22));
        addRenderableWidget(button("\u53CD\u5E94\u5806 -10%", ShipActionPacket.REACTOR_DOWN, x + 78, y + 22));
        addRenderableWidget(button("\u6C14\u95F8\uFF1A\u62BD\u5E72", ShipActionPacket.AIRLOCK_DRAIN, x, y + 44));
        addRenderableWidget(button("\u6C14\u95F8\uFF1A\u6CE8\u6C34", ShipActionPacket.AIRLOCK_FLOOD, x + 78, y + 44));
        addRenderableWidget(button("\u63A8\u8FDB", ShipActionPacket.THROTTLE_FORWARD, x, y + 66));
        addRenderableWidget(button("\u505C\u8F66", ShipActionPacket.STOP, x + 78, y + 66));
    }

    private Button button(String label, int action, int x, int y) {
        return Button.builder(Component.literal(label), b -> ModNetwork.send(
                        new ShipActionPacket(menu.corePos(), action)))
                .bounds(x, y, 74, 20)
                .build();
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, PANEL_BG);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + 2, ACCENT);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, title, leftPos + 8, topPos + 8, TEXT, false);

        int y = topPos + 118;
        for (String line : menu.statusText().split("\uFF5C")) {
            graphics.drawString(font, line.trim(), leftPos + 8, y, TEXT, false);
            y += 11;
            if (y > topPos + imageHeight - 12) {
                break;
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 文字统一在 render() 里画
    }
}
