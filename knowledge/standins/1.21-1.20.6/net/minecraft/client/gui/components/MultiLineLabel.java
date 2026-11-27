package mmt.standin.net.minecraft.client.gui.components;

/**
 * 1.21's MultiLineLabel.renderCentered and renderLeftAligned on 1.20.6, for a port back: the same drawing, nothing returned.
 *
 * Read from both releases: both draw each line the same way (centered on x, or from x, line height apart, with shadow);
 * 1.21's return nothing, 1.20.6's return the y below the last line. renderCentered(graphics, x, y) draws in white in
 * both (1.21's -1, 1.20.6's 16777215, which Font draws opaque).
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`renderCentered`, `renderLeftAligned` no longer return anything".
 */
public final class MultiLineLabel {

    private MultiLineLabel() {}

    public static void renderCentered(net.minecraft.client.gui.components.MultiLineLabel self, net.minecraft.client.gui.GuiGraphics graphics, int x, int y) {
        self.renderCentered(graphics, x, y);
    }

    public static void renderCentered(net.minecraft.client.gui.components.MultiLineLabel self, net.minecraft.client.gui.GuiGraphics graphics, int x, int y, int lineHeight, int color) {
        self.renderCentered(graphics, x, y, lineHeight, color);
    }

    public static void renderLeftAligned(net.minecraft.client.gui.components.MultiLineLabel self, net.minecraft.client.gui.GuiGraphics graphics, int x, int y, int lineHeight, int color) {
        self.renderLeftAligned(graphics, x, y, lineHeight, color);
    }
}
