package mmt.standin.net.minecraft.client.gui;

import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;

/**
 * 1.19.4's ComponentPath.Path on 1.19.3: the class itself, which 1.19.3 does not have.
 *
 * Read from both releases: 1.19.4's applyFocus sets the container's focused child (setFocused(GuiEventListener), which
 * 1.19.3's ContainerEventHandler has too) to the next component, or to none, then applies the rest of the path.
 */
public record ComponentPath$Path(ContainerEventHandler component, ComponentPath childPath) implements ComponentPath {

    @Override
    public void applyFocus(boolean focused) {
        component.setFocused(focused ? childPath.component() : (GuiEventListener) null);
        childPath.applyFocus(focused);
    }
}
