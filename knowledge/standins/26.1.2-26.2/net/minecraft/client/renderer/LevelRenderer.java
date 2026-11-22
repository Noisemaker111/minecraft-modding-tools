package mmt.standin.net.minecraft.client.renderer;

/**
 * 26.1.2's LevelRenderer on 26.2.
 *
 * Read from both releases: 26.1.2's LevelRenderer.allChanged() cleared the level's tint caches and rebuilt every
 * compiled section at once (a new SectionCompiler and ViewArea for the options in force). 26.2 splits that between the
 * client's LevelExtractor and the renderer: LevelExtractor.allChanged() clears the tint caches, resets the section
 * tracker and asks for the rebuild, which the next frame's extract runs (LevelRenderer.invalidateCompiledGeometry with the
 * level, options, camera and block colors). So the old method is the client's LevelExtractor.allChanged(), the rebuild
 * landing on the next frame.
 *
 * Agrees with NeoForge's 26.1.2 -> 26.2 primer (https://github.com/neoforged/.github/blob/main/primers/26.2/index.md, CC BY 4.0): "allChanged -> invalidateCompiledGeometry, not one-to-one".
 */
public final class LevelRenderer {

    private LevelRenderer() {}

    public static void allChanged(net.minecraft.client.renderer.LevelRenderer self) {
        net.minecraft.client.Minecraft.getInstance().levelExtractor.allChanged();
    }
}
