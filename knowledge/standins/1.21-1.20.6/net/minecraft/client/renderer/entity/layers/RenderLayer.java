package mmt.standin.net.minecraft.client.renderer.entity.layers;

/**
 * 1.21's RenderLayer.renderColoredCutoutModel and coloredCutoutModelCopyLayerRender with an ARGB tint on 1.20.6, for a
 * port back: 1.20.6's, given the tint's red, green and blue.
 *
 * Read from both releases: both draw the model with entityCutoutNoCull of the texture, at the entity's overlay; 1.21
 * tints by the ARGB int (Model.renderToBuffer's color), 1.20.6 by three floats with alpha 1, so the int's alpha is not
 * carried (1.20.6 draws these layers opaque).
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`net.minecraft.client.model.Model#renderToBuffer` now takes in an integer representing the ARGB tint instead of four floats".
 */
public final class RenderLayer {

    private RenderLayer() {}

    public static void renderColoredCutoutModel(net.minecraft.client.model.EntityModel model, net.minecraft.resources.ResourceLocation texture, com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers, int light, net.minecraft.world.entity.LivingEntity entity, int color) {
        net.minecraft.client.renderer.entity.layers.RenderLayer.renderColoredCutoutModel(model, texture, pose, buffers, light, entity, (color >> 16 & 255) / 255.0F, (color >> 8 & 255) / 255.0F, (color & 255) / 255.0F);
    }

    public static void coloredCutoutModelCopyLayerRender(net.minecraft.client.model.EntityModel parent, net.minecraft.client.model.EntityModel model, net.minecraft.resources.ResourceLocation texture, com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers, int light, net.minecraft.world.entity.LivingEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, float partialTick, int color) {
        net.minecraft.client.renderer.entity.layers.RenderLayer.coloredCutoutModelCopyLayerRender(parent, model, texture, pose, buffers, light, entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, partialTick, (color >> 16 & 255) / 255.0F, (color >> 8 & 255) / 255.0F, (color & 255) / 255.0F);
    }
}
