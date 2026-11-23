package mmt.standin.net.minecraft.client.gui.components;

import java.util.function.Consumer;

/**
 * 1.21.10's MultiLineTextWidget.setColor and configureStyleHandling on 1.21.11.
 *
 * Read from both releases: 1.21.10's setColor(rgb) set the colour its text was drawn in where the text's own style gave
 * none (the widget's alpha applied), and configureStyleHandling(hover, onClick) chose whether hovering showed a styled
 * part's hover event and what a click on a clickable part did. 1.21.11 draws a string widget's text through the GUI's
 * text collector in the colours of the text's own style, shows hover events whenever the widget is hovered, and keeps
 * the click handler as setComponentClickHandler(onClick). So the colour becomes the message's own style colour where it
 * has none (which every part without its own colour inherits, as it did from the widget's), and the click handler is
 * set; hover events show either way.
 *
 * Agrees with NeoForge's 1.21.10 -> 1.21.11 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.11/index.md, CC BY 4.0):
 * "MultiLineTextWidget#setColor, configureStyleHandling are removed", "AbstractStringWidget ... setColor, getColor are removed".
 */
public final class MultiLineTextWidget {

    private MultiLineTextWidget() {}

    public static net.minecraft.client.gui.components.MultiLineTextWidget setColor(net.minecraft.client.gui.components.MultiLineTextWidget self, int color) {
        mmt.standin.net.minecraft.client.gui.components.AbstractStringWidget.setColor(self, color);
        return self;
    }

    public static net.minecraft.client.gui.components.MultiLineTextWidget configureStyleHandling(net.minecraft.client.gui.components.MultiLineTextWidget self, boolean hover, Consumer onClick) {
        self.setComponentClickHandler(onClick);
        return self;
    }
}
