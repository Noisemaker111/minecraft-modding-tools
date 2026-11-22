package mmt.standin.net.minecraft.client.gui.components;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

/**
 * 1.21.10's AbstractWidget on 1.21.11.
 *
 * Read from both releases: 1.21.10's renderScrollingString drew one line of text in a box: centred (clamped to the box)
 * when it fit, else clipped to the box and slid back and forth over time (a period of max(overflow / 2, 3) seconds), in
 * the colour given; the instance form drew the widget's message in the widget's box less a margin on each side. 1.21.11
 * replaced them with renderScrollingStringOverContents, which hands the same box to an ActiveTextCollector and takes no
 * colour (the text's style colours it). GuiGraphics still has every call the 1.21.10 code made (enableScissor,
 * drawString, drawCenteredString, disableScissor), so this is the 1.21.10 code.
 *
 * Agrees with NeoForge's 1.21.10 -> 1.21.11 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.11/index.md, CC BY 4.0): "renderScrollingString -> renderScrollingStringOverContents, not one-to-one".
 */
public final class AbstractWidget {

    private AbstractWidget() {}

    public static void renderScrollingString(net.minecraft.client.gui.components.AbstractWidget self, GuiGraphics graphics, Font font, int margin, int color) {
        int left = self.getX() + margin;
        int right = self.getX() + self.getWidth() - margin;
        renderScrollingString(graphics, font, self.getMessage(), left, self.getY(), right, self.getY() + self.getHeight(), color);
    }

    public static void renderScrollingString(GuiGraphics graphics, Font font, Component text, int left, int top, int right, int bottom, int color) {
        renderScrollingString(graphics, font, text, (left + right) / 2, left, top, right, bottom, color);
    }

    public static void renderScrollingString(GuiGraphics graphics, Font font, Component text, int center, int left, int top, int right, int bottom, int color) {
        int width = font.width(text);
        int y = (top + bottom - 9) / 2 + 1;
        int room = right - left;
        if (width > room) {
            int overflow = width - room;
            double seconds = Util.getMillis() / 1000.0;
            double period = Math.max(overflow * 0.5, 3.0);
            double phase = Math.sin((Math.PI / 2) * Math.cos((Math.PI * 2) * seconds / period)) / 2.0 + 0.5;
            double shift = Mth.lerp(phase, 0.0, (double) overflow);
            graphics.enableScissor(left, top, right, bottom);
            graphics.drawString(font, text, left - (int) shift, y, color);
            graphics.disableScissor();
        } else {
            int x = Mth.clamp(center, left + width / 2, right - width / 2);
            graphics.drawCenteredString(font, text, x, y, color);
        }
    }
}
