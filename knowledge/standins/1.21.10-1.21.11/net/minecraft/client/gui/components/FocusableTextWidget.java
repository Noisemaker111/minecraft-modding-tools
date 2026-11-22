package mmt.standin.net.minecraft.client.gui.components;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/**
 * 1.21.10's FocusableTextWidget on 1.21.11.
 *
 * Read from both releases: 1.21.10's constructors took the text's maximum width, the message, the font, whether the
 * border always shows (default true), when the background fills (default ALWAYS) and the padding around the text
 * (default 4). 1.21.11 makes it with builder(message, font, padding), whose textWidth(width) is the same maximum text
 * width (it keeps width plus the padding on both sides, as the widget now includes its padding), alwaysShowBorder and
 * backgroundFill the same settings, with the same defaults.
 *
 * Agrees with NeoForge's 1.21.10 -> 1.21.11 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.11/index.md, CC BY 4.0): "FocusableTextWidget constructor is now package private, use builder instead".
 */
public final class FocusableTextWidget {

    private FocusableTextWidget() {}

    public static net.minecraft.client.gui.components.FocusableTextWidget init(int maxWidth, Component message, Font font) {
        return init(maxWidth, message, font, 4);
    }

    public static net.minecraft.client.gui.components.FocusableTextWidget init(int maxWidth, Component message, Font font, int padding) {
        return init(maxWidth, message, font, true, net.minecraft.client.gui.components.FocusableTextWidget.BackgroundFill.ALWAYS, padding);
    }

    public static net.minecraft.client.gui.components.FocusableTextWidget init(int maxWidth, Component message, Font font, boolean alwaysShowBorder,
                                                                               net.minecraft.client.gui.components.FocusableTextWidget.BackgroundFill fill, int padding) {
        return net.minecraft.client.gui.components.FocusableTextWidget.builder(message, font, padding)
            .textWidth(maxWidth)
            .alwaysShowBorder(alwaysShowBorder)
            .backgroundFill(fill)
            .build();
    }
}
