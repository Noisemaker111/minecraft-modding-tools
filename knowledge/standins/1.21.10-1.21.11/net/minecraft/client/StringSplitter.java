package mmt.standin.net.minecraft.client;

import java.util.Optional;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.StringDecomposer;

/**
 * 1.21.10's StringSplitter.componentStyleAtWidth(text, width) on 1.21.11.
 *
 * Read from both releases: 1.21.10's componentStyleAtWidth walked the text's characters with a WidthLimitedCharSink that
 * took each character's width (widthProvider.getWidth(codepoint, style)) off the width it was given, and answered the
 * style of the first character that took it below 0, or null when the whole text fit (the style under the mouse, for
 * clicks and hovers on chat and book lines). For a FormattedText it went through each styled part with
 * StringDecomposer.iterateFormatted and answered the style of the part where that happened. 1.21.11 finds clicked and
 * hovered styles while it draws (ActiveTextCollector) and dropped the method; the width provider and
 * stringWidth(FormattedCharSequence), which adds up the same per-character widths, are kept. So this walks the text the
 * same way and asks stringWidth for one character at a time (FormattedCharSequence.codepoint(codepoint, style)).
 *
 * Agrees with NeoForge's 1.21.10 -> 1.21.11 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.11/index.md, CC BY 4.0), whose StringSplitter removals name "formattedIndexByWidth, componentStyleAtWidth".
 */
public final class StringSplitter {

    private StringSplitter() {}

    public static Style componentStyleAtWidth(net.minecraft.client.StringSplitter self, FormattedCharSequence text, int width) {
        float[] left = {width};
        Style[] found = {null};
        text.accept((index, style, codepoint) -> {
            left[0] -= self.stringWidth(FormattedCharSequence.codepoint(codepoint, style));
            if (left[0] >= 0.0F) {
                return true;
            }
            found[0] = style;
            return false;
        });
        return found[0];
    }

    public static Style componentStyleAtWidth(net.minecraft.client.StringSplitter self, FormattedText text, int width) {
        float[] left = {width};
        Optional result = text.visit((style, part) -> StringDecomposer.iterateFormatted(part, style, (index, charStyle, codepoint) -> {
            left[0] -= self.stringWidth(FormattedCharSequence.codepoint(codepoint, charStyle));
            return left[0] >= 0.0F;
        }) ? Optional.empty() : Optional.of(style), Style.EMPTY);
        return (Style) result.orElse(null);
    }
}
