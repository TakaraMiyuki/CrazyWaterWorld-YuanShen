package com.opamb.barotrauma.client;

import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.world.ModDimensions;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 深度表：只在深海维度里显示，1 格 = 1 米。
 */
@Mod.EventBusSubscriber(modid = BarotraumaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class DepthOverlay {

    private DepthOverlay() {
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (!minecraft.level.dimension().equals(ModDimensions.THE_DEEP)) {
            return;
        }
        int meters = ModDimensions.toMeters(minecraft.player.blockPosition().getY());
        Component text = Component.literal("深度 " + meters + " m").withStyle(ChatFormatting.AQUA);
        GuiGraphics graphics = event.getGuiGraphics();
        graphics.drawString(minecraft.font, text, 8, 8, 0xFFFFFF, true);
    }
}
