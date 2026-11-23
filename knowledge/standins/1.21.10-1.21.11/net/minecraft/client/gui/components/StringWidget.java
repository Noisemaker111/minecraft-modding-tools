package mmt.standin.net.minecraft.client.gui.components;

/**
 * 1.21.10's StringWidget.setColor on 1.21.11: as AbstractStringWidget's (see its stand-in), answering the widget itself.
 *
 * Agrees with NeoForge's 1.21.10 -> 1.21.11 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.11/index.md, CC BY 4.0):
 * "StringWidget#setColor is removed".
 */
public final class StringWidget {

    private StringWidget() {}

    public static net.minecraft.client.gui.components.StringWidget setColor(net.minecraft.client.gui.components.StringWidget self, int color) {
        mmt.standin.net.minecraft.client.gui.components.AbstractStringWidget.setColor(self, color);
        return self;
    }
}
