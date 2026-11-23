package mmt.standin.net.minecraft.client.renderer;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.resources.Identifier;

/**
 * 26.2's ShaderManager.getShader on 26.3.
 *
 * Read from both releases: 26.2's getShader(id, type) answered the source of a loaded shader, or null, from the configs
 * the last reload read (its compilation cache's shaderSources, keyed by id and type). 26.3 moved that lookup onto the
 * configs themselves (ShaderManager.Configs.getShader(id, type), the same map and key); the last reload hands those configs
 * to the PipelineCache it makes current (RenderSystem.setCurrentPipelineCache(new PipelineCache(device, configs))), which
 * keeps them as its shaderSource, so the old call reads them there (null before the first reload, as 26.2's was empty).
 *
 * Agrees with NeoForge's 26.2 -> 26.3 primer (https://github.com/neoforged/.github/blob/main/primers/26.3/index.md, CC BY 4.0):
 * "ShaderManager ... getShader -> ShaderSource#getShader" (ShaderType itself moved to com.mojang.renderpearl.api.pipeline, which the route follows).
 */
public final class ShaderManager {

    private ShaderManager() {}

    public static String getShader(net.minecraft.client.renderer.ShaderManager self, Identifier id, ShaderType type) {
        PipelineCache cache = RenderSystem.currentPipelineCache;
        return cache == null ? null : cache.shaderSource.getShader(id, type);
    }
}
