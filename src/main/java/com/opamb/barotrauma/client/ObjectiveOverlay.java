package com.opamb.barotrauma.client;

import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.mission.Mission;
import com.opamb.barotrauma.ship.vs.VsState;
import com.opamb.barotrauma.world.ModDimensions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 目标提示 HUD：在深度表下方常驻一行"现在该干什么"。
 *
 * <p>任务系统（P2）接入前，这里显示的是新手引导目标；接入后优先显示
 * {@link #override}（当前任务的一句话目标），没有任务再退回引导文案。</p>
 *
 * <p>注意：物理船状态 {@link VsState#active()} 是服务端静态，单机（集成服务端同 JVM）
 * 下客户端读得到；专用服务器上客户端读不到，会一直显示"登船"引导——这是已知的
 * MVP 限制，靠泊后的真实目标由任务系统同步给客户端。</p>
 */
@Mod.EventBusSubscriber(modid = BarotraumaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ObjectiveOverlay {

    /** 任务系统设置的当前目标（null = 用默认引导文案）。 */
    public static volatile String override;

    private static final int ACCENT = 0xFFE0762F;
    private static final int SAILING = 0xFF7BE38B;

    private ObjectiveOverlay() {
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null
                || minecraft.options.hideGui) {
            return;
        }
        if (!minecraft.level.dimension().equals(ModDimensions.THE_DEEP)) {
            return;
        }
        String text = override != null ? override : defaultObjective();
        if (text == null || text.isEmpty()) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        Font font = minecraft.font;
        Component line = Component.literal("▸ " + text)
                .withStyle(net.minecraft.ChatFormatting.GRAY);
        // 底色垫一层，保证雪地/亮色地形上也读得清。
        graphics.fill(6, 20, 9 + font.width(line), 31, 0x66000000);
        graphics.drawString(font, line, 8, 22, override != null ? ACCENT : hintColor(), true);
    }

    private static int hintColor() {
        Mission mission = ClientMissionCache.active();
        if (mission != null && mission.state() == Mission.State.READY) {
            return SAILING;
        }
        return VsState.active() ? SAILING : ACCENT;
    }

    private static String defaultObjective() {
        Mission mission = ClientMissionCache.active();
        if (mission != null) {
            int dist = (int) Math.sqrt(minecraft().player.blockPosition().distSqr(mission.target));
            String where = mission.type == com.opamb.barotrauma.mission.MissionType.DELIVERY
                    ? "B 中继站" : mission.target.getX() + ", " + mission.target.getY() + ", " + mission.target.getZ();
            if (mission.state() == Mission.State.READY) {
                return "任务达成！回哨站终端交付（+" + mission.reward + "）";
            }
            return String.format("任务 #%d：前往 %s（%dm）", mission.id, where, dist);
        }
        if (VsState.active()) {
            return "出航中：WASD/舵台操舵 · 返航后在声纳台点「靠泊」";
        }
        return "目标：登上码头的初始潜艇，右键声纳台打开舵台，点「下潜」";
    }

    private static net.minecraft.client.Minecraft minecraft() {
        return net.minecraft.client.Minecraft.getInstance();
    }
}
