package mmt.standin.net.minecraft.client.renderer;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.resources.Identifier;

/**
 * 26.2's ShaderManager.getShader on 26.3.
 *
 * Read from both releases: 26.2's getShader(id, type) answered the source of a loaded shader, or null, from the configs
 * the last reload read (its compilation cache's shaderSources, keyed by id and type). 26.3 moved that lookup onto the
 * configs themselves (ShaderManager.Configs.getShader(id, type), the same map and key) and keeps the configs of the last
 * reload in its post chain cache (postChains.configs), so the old call reads them there.
 *
 * Agrees with NeoForge's 26.2 -> 26.3 primer (https://github.com/neoforged/.github/blob/main/primers/26.3/index.md, CC BY 4.0):
 * "ShaderManager ... getShader -> ShaderSource#getShader" (ShaderType itself moved to com.mojang.renderpearl.api.pipeline, which the route follows).
 */
public final class ShaderManager {

    private ShaderManager() {}

    public static String getShader(net.minecraft.client.renderer.ShaderManager self, Identifier id, ShaderType type) {
        return self.postChains.configs.getShader(id, type);
    }
}
