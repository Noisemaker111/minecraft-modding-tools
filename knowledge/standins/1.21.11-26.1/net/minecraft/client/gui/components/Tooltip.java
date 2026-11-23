package mmt.standin.net.minecraft.client.gui.components;

import net.minecraft.network.chat.Component;

/**
 * 1.21.11's Tooltip on 26.1.
 *
 * Read from both releases: 1.21.11's constructor Tooltip(message, narration) (private, which a mod reaches through its
 * access widener or transformer) made the tooltip that create(message, narration) gave. 26.1's constructor also takes
 * a tooltip image (Optional) and a background sprite, and its create(message, narration) passes none of either
 * (Optional.empty(), null): the same tooltip as before.
 */
public final class Tooltip {

    private Tooltip() {}

    public static net.minecraft.client.gui.components.Tooltip init(Component message, Component narration) {
        return net.minecraft.client.gui.components.Tooltip.create(message, narration);
    }
}
