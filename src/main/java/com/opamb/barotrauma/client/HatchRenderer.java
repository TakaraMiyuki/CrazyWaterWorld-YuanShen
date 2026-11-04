package com.opamb.barotrauma.client;

import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.blockentity.HatchBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedBlockGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * 舱口渲染器。
 *
 * <p>资源和 {MOD_ID} 的约定一致：{@code geo/block/hatch.geo.json}、
 * {@code animations/block/hatch.animation.json}、{@code textures/block/hatch.png}。
 * GeoBlockRenderer 自己会把模型移到方块中心并按 {@code FACING} 旋转，
 * 所以模型坐标必须是以 0 为中心的（生成器已经做了 -8/-8 平移）。</p>
 */
public class HatchRenderer extends GeoBlockRenderer<HatchBlockEntity> {

    public HatchRenderer() {
        super(new DefaultedBlockGeoModel<>(
                new ResourceLocation(BarotraumaMod.MOD_ID, "hatch")));
    }
}
