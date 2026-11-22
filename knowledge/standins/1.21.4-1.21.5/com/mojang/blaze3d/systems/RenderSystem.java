package mmt.standin.com.mojang.blaze3d.systems;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.21.4's RenderSystem on 1.21.5.
 *
 * Read from both releases: 1.21.4's defaultBlendFunc() set the GL blend function (SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE,
 * ZERO) for the draws after it, and setShaderTexture(i, location) put the texture manager's texture for location in
 * shader texture slot i. 1.21.5 has no global blend state a mod sets: every draw's RenderPipeline carries its blend
 * function, which GlCommandEncoder.applyPipelineState applies before the draw (the GUI's translucent pipelines use
 * SRC_ALPHA, ONE_MINUS_SRC_ALPHA), so the blend the old call asked for is the one the following draws already get.
 * Slot i now holds a GpuTexture (RenderSystem.setShaderTexture(int, GpuTexture)), which a texture gives with getTexture().
 *
 * Agrees with NeoForge's 1.21.4 -> 1.21.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.5/index.md, CC BY 4.0): "defaultBlendFunc is removed", "setShaderTexture now takes in a GpuTexture instead of a bind address", and the Render Pipeline Rework section: blending is set on the pipeline.
 */
public final class RenderSystem {

    private RenderSystem() {}

    public static void defaultBlendFunc() {
    }

    public static void setShaderTexture(int slot, ResourceLocation location) {
        com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(slot, Minecraft.getInstance().getTextureManager().getTexture(location).getTexture());
    }
}
