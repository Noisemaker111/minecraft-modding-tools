package mmt.standin.net.minecraft.client.gui.components;

import net.minecraft.network.chat.Component;

/**
 * 1.21.10's AbstractStringWidget.setColor on 1.21.11.
 *
 * Read from both releases: 1.21.10's setColor(rgb) set the colour a string widget drew its text in where the text's own
 * style gave none (the widget's alpha applied). 1.21.11 draws a string widget's text through the GUI's text collector in
 * the colours of the text's own style, keeping no widget colour, so the colour becomes the message's own style colour
 * where it has none, which every part without a colour of its own inherits as it did from the widget.
 *
 * Agrees with NeoForge's 1.21.10 -> 1.21.11 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.11/index.md, CC BY 4.0):
 * "AbstractStringWidget ... setColor, getColor are removed"; "StringWidget#setColor is removed".
 */
public final class AbstractStringWidget {

    private AbstractStringWidget() {}

    public static net.minecraft.client.gui.components.AbstractStringWidget setColor(net.minecraft.client.gui.components.AbstractStringWidget self, int color) {
        Component message = self.getMessage();
        if (message.getStyle().getColor() == null) {
            self.setMessage(message.copy().withColor(color & 0xFFFFFF));
        }
        return self;
    }
}
