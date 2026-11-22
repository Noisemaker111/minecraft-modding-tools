package mmt.standin.net.minecraft.client.gui.components;

import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * 1.20.6's MultiLineLabel on 1.21.
 *
 * Read from both releases: 1.20.6's create(font, text, width[, maxLines]) split a FormattedText into lines no wider than
 * width (at most maxLines). 1.21's create(font, width, maxLines, components...) splits components the same way (and
 * again when the language changes) and takes no FormattedText, so a text that is not a component is made one, each run
 * keeping its style.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0): "create methods with FormattedText has been removed", "create methods with Lists now take in a component varargs".
 */
public final class MultiLineLabel {

    private MultiLineLabel() {}

    private static Component component(FormattedText text) {
        if (text instanceof Component component) {
            return component;
        }
        MutableComponent built = Component.empty();
        text.visit((style, run) -> {
            built.append(Component.literal(run).withStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        return built;
    }

    public static net.minecraft.client.gui.components.MultiLineLabel create(Font font, FormattedText text, int width) {
        return net.minecraft.client.gui.components.MultiLineLabel.create(font, width, Integer.MAX_VALUE, component(text));
    }

    public static net.minecraft.client.gui.components.MultiLineLabel create(Font font, FormattedText text, int width, int maxLines) {
        return net.minecraft.client.gui.components.MultiLineLabel.create(font, width, maxLines, component(text));
    }
}
