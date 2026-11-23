package mmt.standin.net.minecraft.client.gui.components;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2f;

/**
 * 1.21.10's MultiLineLabel on 1.21.11.
 *
 * Read from both releases: 1.21.10's render(graphics, align, x, y, lineHeight, shadow, color) drew each line with
 * graphics.drawString(font, line, align.calculateLeft(x, width), y, color), lineHeight apart (shadow was not read: that
 * drawString draws with a shadow), and gave the y below the last line. 1.21.11's visitLines(alignment, x, y,
 * lineHeight, collector) walks the same lines the same way, handing each to the collector at the left edge it worked
 * out, and gives the same y; MultiLineLabel$Align became TextAlignment. So the old call is visitLines with a collector
 * that draws each line as the old one did. The label's font is not kept where a caller can reach it in 1.21.11; the
 * lines are drawn with the game's font, which labels are made with.
 *
 * Agrees with NeoForge's 1.21.10 -> 1.21.11 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.11/index.md, CC BY 4.0): "MultiLineLabel: render, getStyle -> visitLines, not one-to-one; $Align -> TextAlignment", and its text section: text is submitted to an ActiveTextCollector with accept.
 */
public final class MultiLineLabel {

    private MultiLineLabel() {}

    public static int render(net.minecraft.client.gui.components.MultiLineLabel self, GuiGraphics graphics, TextAlignment align, int x, int y, int lineHeight, boolean shadow, int color) {
        Font font = Minecraft.getInstance().font;
        return self.visitLines(align, x, y, lineHeight, new ActiveTextCollector() {
            private ActiveTextCollector.Parameters parameters = new ActiveTextCollector.Parameters(new Matrix3x2f());

            public ActiveTextCollector.Parameters defaultParameters() {
                return parameters;
            }

            public void defaultParameters(ActiveTextCollector.Parameters given) {
                parameters = given;
            }

            public void accept(TextAlignment alignment, int left, int top, ActiveTextCollector.Parameters given, FormattedCharSequence line) {
                graphics.drawString(font, line, alignment.calculateLeft(left, font, line), top, color);
            }

            public void acceptScrolling(Component text, int centerX, int left, int right, int top, int bottom, ActiveTextCollector.Parameters given) {
                graphics.drawString(font, text.getVisualOrderText(), left, top, color);
            }
        });
    }
}
