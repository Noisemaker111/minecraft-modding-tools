package mmt.standin.com.mojang.blaze3d.platform;

/**
 * 1.14.4's GlStateManager.enableLight(light) on 1.15.
 *
 * Read from both releases: 1.14.4's enableLight(i) enabled the cached GL state of fixed-function light i
 * (LIGHT_ENABLE[i].enable(), which calls glEnable(GL_LIGHT0 + i) when the cache says it is off). 1.15 renamed
 * GlStateManager's state methods with a leading underscore (RenderSystem calls them), and _enableLight(i) makes the same
 * call on the same cached state after checking it runs on the render thread. So the old method is the renamed one.
 *
 * NeoForge's 1.15 primer (https://github.com/neoforged/.github/blob/main/primers/1.15/index.md) says only to drop GL setup
 * the batched renderer now does; it does not name this method, so this is from the game's code alone.
 */
public final class GlStateManager {

    private GlStateManager() {}

    public static void enableLight(int light) {
        com.mojang.blaze3d.platform.GlStateManager._enableLight(light);
    }
}
