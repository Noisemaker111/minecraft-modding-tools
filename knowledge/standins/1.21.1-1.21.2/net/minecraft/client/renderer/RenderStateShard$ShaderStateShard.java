package mmt.standin.net.minecraft.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.function.Supplier;
import net.minecraft.client.renderer.CompiledShaderProgram;
import net.minecraft.client.renderer.RenderStateShard;

/**
 * 1.21.1's new RenderStateShard.ShaderStateShard(Supplier<ShaderInstance>) on 1.21.2.
 *
 * Read from both releases: 1.21.1's shard, when set up, called RenderSystem.setShader(supplier), which made the supplied
 * ShaderInstance the current shader, and set nothing back when cleared. 1.21.2's shard takes a ShaderProgram key and sets
 * it with RenderSystem.setShader(program), which looks up the compiled program; RenderSystem.setShader(CompiledShaderProgram)
 * still sets a compiled one directly, and ShaderInstance is now CompiledShaderProgram. A supplier is no key, so the shard
 * is the empty one with its setup doing what 1.21.1's did: set the supplied program each time it is set up.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "ShaderInstance -> CompiledShaderProgram" and "new ShaderStateShard(MY_SHADER)" with a ShaderProgram.
 */
public final class RenderStateShard$ShaderStateShard {

    private RenderStateShard$ShaderStateShard() {}

    public static RenderStateShard.ShaderStateShard init(Supplier supplier) {
        return new RenderStateShard.ShaderStateShard() {
            @Override
            public void setupRenderState() {
                RenderSystem.setShader((CompiledShaderProgram) supplier.get());
            }
        };
    }
}
