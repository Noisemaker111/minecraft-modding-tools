package mmt.standin.net.minecraft.client.renderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.21.1's GameRenderer.currentEffect() on 1.21.2.
 *
 * Read from both releases: 1.21.1 kept the entity view's post effect (creeper, spider, invert, or a mod's) as a PostChain
 * it loaded itself, and currentEffect() returned it, null when none. 1.21.2 keeps only the effect's id (currentPostEffect())
 * and asks the ShaderManager for its chain each frame (getPostChain(id, LevelTargetBundle.MAIN_TARGETS)), which loads it
 * once and gives the same chain after. So the old chain is that one, or null with no effect.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "currentEffect -> currentPostEffect" and "Minecraft#getShaderManager - Returns the manager that loads all the shaders and post effects".
 */
public final class GameRenderer {

    private GameRenderer() {}

    public static PostChain currentEffect(net.minecraft.client.renderer.GameRenderer self) {
        ResourceLocation id = self.currentPostEffect();
        return id == null ? null : Minecraft.getInstance().getShaderManager().getPostChain(id, LevelTargetBundle.MAIN_TARGETS);
    }
}
