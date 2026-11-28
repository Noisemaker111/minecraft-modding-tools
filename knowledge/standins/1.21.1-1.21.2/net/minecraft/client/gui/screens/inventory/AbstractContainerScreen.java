package mmt.standin.net.minecraft.client.gui.screens.inventory;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;

/**
 * 1.21.1's AbstractContainerScreen.renderSlotHighlight(graphics, x, y, z) on 1.21.2.
 *
 * Read from both releases: 1.21.1's static helper filled the 16 by 16 slot at x, y with a translucent white
 * (fillGradient(RenderType.guiOverlay(), x, y, x + 16, y + 16, 0x80FFFFFF, 0x80FFFFFF, z)), and mods drew their own slots'
 * highlight with it. 1.21.2 draws the hovered slot with two sprites in private methods and dropped the helper; GuiGraphics
 * still has fillGradient(RenderType, x1, y1, x2, y2, from, to, z) and RenderType.guiOverlay(). So it is that fill, as
 * 1.21.1 drew it.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "AbstractContainerScreen#renderSlotHighlight -> renderSlotHighlightBack, renderSlotHighlightFront, now private".
 */
public final class AbstractContainerScreen {

    private AbstractContainerScreen() {}

    public static void renderSlotHighlight(GuiGraphics graphics, int x, int y, int z) {
        graphics.fillGradient(RenderType.guiOverlay(), x, y, x + 16, y + 16, -2130706433, -2130706433, z);
    }
}
