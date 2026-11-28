package mmt.standin.net.minecraft.client.gui.screens.inventory.tooltip;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 1.21.1's TooltipRenderUtil.renderTooltipBackground(graphics, x, y, width, height, z) on 1.21.2.
 *
 * Read from both releases: 1.21.1 drew the vanilla tooltip box (dark fill, purple gradient frame) around the area at z.
 * 1.21.2 draws the same box from sprites and takes one more argument, the item's tooltip style, where null is the
 * vanilla background and frame (tooltip/background, tooltip/frame). GuiGraphics passes null for an item with no style.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "TooltipRenderUtil#renderTooltipBackground now has a parameter to take in the relative directory of the background and frame textures of the tooltip, or the default if null".
 */
public final class TooltipRenderUtil {

    private TooltipRenderUtil() {}

    public static void renderTooltipBackground(GuiGraphics graphics, int x, int y, int width, int height, int z) {
        net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil.renderTooltipBackground(graphics, x, y, width, height, z, null);
    }
}
