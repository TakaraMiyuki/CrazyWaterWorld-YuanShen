package com.opamb.barotrauma.client;

import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.registry.ModBlockEntities;
import com.opamb.barotrauma.registry.ModShipBlocks;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 客户端初始化：渲染层、方块实体渲染器。
 */
@Mod.EventBusSubscriber(modid = BarotraumaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ItemBlockRenderTypes.setRenderLayer(ModShipBlocks.HULL_GLASS.get(), RenderType.cutout());
            net.minecraft.client.gui.screens.MenuScreens.register(
                    com.opamb.barotrauma.registry.ModMenus.SHIP_CONSOLE.get(), ShipConsoleScreen::new);
            net.minecraft.client.gui.screens.MenuScreens.register(
                    com.opamb.barotrauma.registry.ModMenus.SONAR.get(), SonarScreen::new);
        });
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.SONAR.get(), SonarRenderer::new);
        event.registerEntityRenderer(com.opamb.barotrauma.registry.ModEntities.VESSEL.get(), VesselRenderer::new);
    }
}
