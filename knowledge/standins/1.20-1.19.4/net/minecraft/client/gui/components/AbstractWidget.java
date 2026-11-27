package mmt.standin.net.minecraft.client.gui.components;

import net.minecraft.client.gui.components.Tooltip;

/**
 * 1.20's AbstractWidget.getTooltip() on 1.19.4.
 *
 * Read from both releases: both keep a widget's tooltip in the same private field (tooltip, set by setTooltip); 1.20
 * added the getter that hands it back.
 */
public final class AbstractWidget {

    private AbstractWidget() {
    }

    public static Tooltip getTooltip(net.minecraft.client.gui.components.AbstractWidget self) {
        return self.tooltip;
    }
}
