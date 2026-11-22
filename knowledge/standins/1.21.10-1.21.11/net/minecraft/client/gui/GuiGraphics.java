package mmt.standin.net.minecraft.client.gui;

/**
 * 1.21.10's GuiGraphics on 1.21.11.
 *
 * Read from both releases: 1.21.10's textHighlight(x1, y1, x2, y2) filled the box with the invert pipeline and then the
 * text-highlight pipeline; 1.21.11's textHighlight(x1, y1, x2, y2, invertBackground) does the second always and the
 * first only when asked. Asking for it is the 1.21.10 drawing.
 *
 * Agrees with NeoForge's 1.21.10 -> 1.21.11 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.11/index.md, CC BY 4.0): "textHighlight now takes in a boolean of whether to render the background rectangle".
 */
public final class GuiGraphics {

    private GuiGraphics() {}

    public static void textHighlight(net.minecraft.client.gui.GuiGraphics self, int x1, int y1, int x2, int y2) {
        self.textHighlight(x1, y1, x2, y2, true);
    }
}
