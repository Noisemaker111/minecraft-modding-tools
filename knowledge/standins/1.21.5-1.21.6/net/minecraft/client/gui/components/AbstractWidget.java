package mmt.standin.net.minecraft.client.gui.components;

import net.minecraft.client.gui.components.Tooltip;

/**
 * 1.21.5's AbstractWidget on 1.21.6.
 *
 * Read from both releases: 1.21.5's getTooltip() returned tooltip.get(), the tooltip its WidgetTooltipHolder held (null
 * for none). 1.21.6 keeps the same private holder, set by setTooltip and read with WidgetTooltipHolder.get(), and drops
 * the getter.
 *
 * Agrees with NeoForge's 1.21.5 -> 1.21.6 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.6/index.md, CC BY 4.0): "AbstractWidget#getTooltip is removed".
 */
public final class AbstractWidget {

    private AbstractWidget() {}

    public static Tooltip getTooltip(net.minecraft.client.gui.components.AbstractWidget self) {
        return self.tooltip.get();
    }
}
