import java.util.*;
import java.util.regex.*;

/** GUI colours that need their alpha written out since the game stopped assuming it. */
final class GuiColorMigration {

    /** The colour argument's position in each text draw call (mojmap GuiGraphics and yarn DrawContext). */
    private static final Map<String, Integer> COLOR_ARGUMENT = Map.of(
        "drawString",
        4,
        "drawCenteredString",
        4,
        "drawWordWrap",
        5,
        "drawText",
        4,
        "drawTextWithShadow",
        4,
        "drawCenteredTextWithShadow",
        4,
        "drawWrappedText",
        5
    );

    /**
     * 1.21.6 draws GUI colours as ARGB: before it, text with no alpha byte was drawn opaque, after it, invisible. Gives
     * an alpha byte to a 6-digit hex literal, to a decimal colour in a text draw call's colour position, and to a
     * decimal literal assigned to a name with "colo(u)r" in it.
     */
    static String opaqueGuiColors(String text) {
        text = Pattern.compile("\\b0[xX]([0-9a-fA-F]{6})\\b(?![0-9a-fA-F])").matcher(text).replaceAll("0xff$1");
        Matcher named = Pattern.compile("(\\b\\w*[Cc]olou?r\\w*\\s*=\\s*)(\\d+)\\b(?![.\\dLlFfDd])").matcher(text);
        StringBuilder out = new StringBuilder();
        while (named.find()) {
            long value = Long.parseLong(named.group(2));
            named.appendReplacement(
                out,
                Matcher.quoteReplacement(
                    value > 0 && value < 0x1000000L
                        ? named.group(1) + String.format(Locale.ROOT, "0xff%06x", value)
                        : named.group()
                )
            );
        }
        named.appendTail(out);
        text = out.toString();
        Matcher call = Pattern.compile("\\.(" + String.join("|", COLOR_ARGUMENT.keySet()) + ")\\(").matcher(text);
        StringBuilder result = new StringBuilder();
        int copied = 0;
        while (call.find()) {
            List<int[]> arguments = JavaText.arguments(text, call.end() - 1);
            int position = COLOR_ARGUMENT.get(call.group(1));
            if (position >= arguments.size()) continue;
            int[] span = arguments.get(position);
            String argument = text.substring(span[0], span[1]);
            if (!argument.strip().matches("\\d+")) continue;
            long value = Long.parseLong(argument.strip());
            if (value >= 0x1000000L) continue;
            result
                .append(text, copied, span[0])
                .append(argument.replace(argument.strip(), String.format(Locale.ROOT, "0xff%06x", value)));
            copied = span[1];
        }
        return result.append(text.substring(copied)).toString();
    }
}
