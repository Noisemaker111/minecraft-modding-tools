package mmt.standin.net.minecraft.client.gui.components;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 1.21.8's MultiLineLabel on 1.21.9.
 *
 * Read from both releases: 1.21.8's renderLeftAligned(graphics, x, y, lineHeight, color) drew each line at x with
 * graphics.drawString(font, line, x, y, color), lineHeight apart; renderCentered(graphics, x, y, lineHeight, color) drew
 * each centred on x (x - width / 2) the same way, and renderCentered(graphics, x, y) was that with 9 and -1 (white).
 * 1.21.9's render(graphics, align, x, y, lineHeight, shadow, color) draws each line at align.calculateLeft(x, width)
 * with the same drawString (shadow is not read), lineHeight apart: LEFT is x, CENTER x - width / 2. (1.21.8's
 * renderLeftAlignedNoShadow drew without a shadow, which render cannot, so it stays reported.)
 *
 * Agrees with NeoForge's 1.21.8 -> 1.21.9 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.9/index.md, CC BY 4.0): "renderCentered, renderLeftAligned, renderLeftAlignedNoShadow -> render, not one-to-one".
 */
public final class MultiLineLabel {

    private MultiLineLabel() {}

    public static void renderLeftAligned(net.minecraft.client.gui.components.MultiLineLabel self, GuiGraphics graphics, int x, int y, int lineHeight, int color) {
        self.render(graphics, net.minecraft.client.gui.components.MultiLineLabel.Align.LEFT, x, y, lineHeight, true, color);
    }

    public static void renderCentered(net.minecraft.client.gui.components.MultiLineLabel self, GuiGraphics graphics, int x, int y, int lineHeight, int color) {
        self.render(graphics, net.minecraft.client.gui.components.MultiLineLabel.Align.CENTER, x, y, lineHeight, true, color);
    }

    public static void renderCentered(net.minecraft.client.gui.components.MultiLineLabel self, GuiGraphics graphics, int x, int y) {
        renderCentered(self, graphics, x, y, 9, -1);
    }
}
