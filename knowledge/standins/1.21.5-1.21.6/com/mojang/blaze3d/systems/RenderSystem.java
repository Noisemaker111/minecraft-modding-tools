package mmt.standin.com.mojang.blaze3d.systems;

/**
 * 1.21.5's RenderSystem on 1.21.6.
 *
 * Read from both releases: 1.21.5's enableScissor(x, y, width, height) and disableScissor() set RenderSystem.SCISSOR_STATE,
 * which RenderType.draw read to clip its render pass. 1.21.6 keeps the same ScissorState for render type draws behind
 * enableScissorForRenderTypeDraws(x, y, width, height), disableScissorForRenderTypeDraws() and
 * getScissorStateForRenderTypeDraws(), which RenderType.draw reads the same way; the GUI clips with its own scissor stack.
 *
 * Agrees with NeoForge's 1.21.5 -> 1.21.6 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.6/index.md, CC BY 4.0): "enableScissor, disableScissor -> enableScissorForRenderTypeDraws, disableScissorForRenderTypeDraws, not one to one".
 */
public final class RenderSystem {

    private RenderSystem() {}

    public static void enableScissor(int x, int y, int width, int height) {
        com.mojang.blaze3d.systems.RenderSystem.enableScissorForRenderTypeDraws(x, y, width, height);
    }

    public static void disableScissor() {
        com.mojang.blaze3d.systems.RenderSystem.disableScissorForRenderTypeDraws();
    }
}
