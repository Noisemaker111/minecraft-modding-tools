package mmt.standin.net.minecraft.client.gui.screens.inventory.tooltip;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.21.5's TooltipRenderUtil.renderTooltipBackground on 1.21.6.
 *
 * Read from both releases: 1.21.5's renderTooltipBackground(graphics, x, y, width, height, z, style) drew the tooltip's
 * background and frame sprites around the box, translated to depth z so it lay over what was drawn before. 1.21.6's takes
 * the same arguments but z and draws the same two sprites over the same box; its GUI puts each draw above any earlier one
 * it overlaps, so the depth has nothing left to do.
 *
 * Agrees with NeoForge's 1.21.5 -> 1.21.6 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.6/index.md, CC BY 4.0):
 * "TooltipRenderUtil#renderTooltipBackground no longer takes in a Z offset".
 */
public final class TooltipRenderUtil {

    private TooltipRenderUtil() {}

    public static void renderTooltipBackground(GuiGraphics graphics, int x, int y, int width, int height, int z, ResourceLocation style) {
        net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil.renderTooltipBackground(graphics, x, y, width, height, style);
    }
}
