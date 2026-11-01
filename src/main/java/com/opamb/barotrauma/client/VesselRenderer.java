package com.opamb.barotrauma.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.opamb.barotrauma.entity.VesselEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * 按调色板把船体方块画出来。每条船一个实体，方块是它的局部坐标。
 */
public class VesselRenderer extends EntityRenderer<VesselEntity> {

    private static final ResourceLocation PLACEHOLDER =
            new ResourceLocation("minecraft", "textures/misc/white.png");

    private final BlockRenderDispatcher dispatcher;

    public VesselRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.dispatcher = context.getBlockRenderDispatcher();
    }

    @Override
    public void render(VesselEntity entity, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        ClientVesselCache.Palette palette = ClientVesselCache.get(entity.getId());
        if (palette == null) {
            return;
        }
        pose.pushPose();
        for (int i = 0; i < palette.states().size(); i++) {
            BlockPos offset = palette.offsets().get(i);
            pose.pushPose();
            pose.translate(offset.getX(), offset.getY(), offset.getZ());
            dispatcher.renderSingleBlock(palette.states().get(i), pose, buffers, packedLight,
                    OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        pose.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(VesselEntity entity) {
        return PLACEHOLDER;
    }
}
