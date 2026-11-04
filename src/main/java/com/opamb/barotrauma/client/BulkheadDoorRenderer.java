package com.opamb.barotrauma.client;

import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.blockentity.BulkheadDoorBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedBlockGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * 舱壁门渲染器。
 *
 * <p>资源路径：{@code geo/block/bulkhead_door.geo.json}、
 * {@code animations/block/bulkhead_door.animation.json}、
 * {@code textures/block/bulkhead_door.png}。</p>
 */
public class BulkheadDoorRenderer extends GeoBlockRenderer<BulkheadDoorBlockEntity> {

    public BulkheadDoorRenderer() {
        super(new DefaultedBlockGeoModel<>(
                new ResourceLocation(BarotraumaMod.MOD_ID, "bulkhead_door")));
    }
}
