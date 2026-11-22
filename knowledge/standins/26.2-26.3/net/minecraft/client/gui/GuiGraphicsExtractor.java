package mmt.standin.net.minecraft.client.gui;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;

/**
 * 26.2's GuiGraphicsExtractor on 26.3.
 *
 * Read from both releases: 26.2's tooltip(font, lines, x, y, positioner, style) always left two pixels between the
 * first line and the rest (the height starting at 0 rather than -2 for more than one line, and + 2 after line 0). 26.3
 * makes that space an argument (extraSpaceAfterFirstLine), the same steps with it true. So the old method is the new one
 * with the space.
 *
 * Agrees with NeoForge's 26.2 -> 26.3 primer (https://github.com/neoforged/.github/blob/main/primers/26.3/index.md, CC BY 4.0): "setTooltipForNextFrame, tooltip now have an overload that takes in a boolean of whether to have a bit more space between the first line and the rest".
 */
public final class GuiGraphicsExtractor {

    private GuiGraphicsExtractor() {}

    public static void tooltip(net.minecraft.client.gui.GuiGraphicsExtractor self, Font font, List lines, int x, int y, ClientTooltipPositioner positioner, Identifier style) {
        self.tooltip(font, lines, x, y, positioner, style, true);
    }
}
