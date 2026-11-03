package com.opamb.barotrauma.client;

import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.registry.ModBlockEntities;
import com.opamb.barotrauma.registry.ModShipBlocks;
import com.opamb.barotrauma.world.OceanEnvironment;
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
            OceanEnvironment.setDryZone(ShipWaterRenderer::isInsideDryZone);
            ItemBlockRenderTypes.setRenderLayer(ModShipBlocks.HULL_GLASS.get(), RenderType.cutout());
            // 测试船壳要真的透过去看见里面，cutout 只有全透明/全不透明两态，不够
            ItemBlockRenderTypes.setRenderLayer(ModShipBlocks.TEST_HULL.get(), RenderType.translucent());
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
