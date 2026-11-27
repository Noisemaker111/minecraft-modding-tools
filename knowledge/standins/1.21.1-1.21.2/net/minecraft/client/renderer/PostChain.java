package mmt.standin.net.minecraft.client.renderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.21.1's PostChain.getName() on 1.21.2.
 *
 * Read from both releases: 1.21.1's chain kept the location it was loaded from as its name (GameRenderer loaded
 * "minecraft:shaders/post/creeper.json" and the like, the blur "minecraft:shaders/post/blur.json"). 1.21.2's chains keep
 * no name: the ShaderManager loads one per id ("minecraft:creeper", the blur "minecraft:blur") and gives the same chain for
 * the same id. So a chain's name is the id the game renderer holds it under, written as 1.21.1 wrote the location; a chain
 * the game renderer does not hold has no name to give.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "currentEffect -> currentPostEffect"; the name is from the game's code alone.
 */
public final class PostChain {

    private static final ResourceLocation BLUR = ResourceLocation.withDefaultNamespace("blur");

    private PostChain() {}

    public static String getName(net.minecraft.client.renderer.PostChain self) {
        Minecraft minecraft = Minecraft.getInstance();
        ResourceLocation effect = minecraft.gameRenderer.currentPostEffect();
        for (ResourceLocation id : new ResourceLocation[] {effect, BLUR}) {
            if (id != null && minecraft.getShaderManager().getPostChain(id, LevelTargetBundle.MAIN_TARGETS) == self) {
                return id.getNamespace() + ":shaders/post/" + id.getPath() + ".json";
            }
        }
        return "";
    }
}
