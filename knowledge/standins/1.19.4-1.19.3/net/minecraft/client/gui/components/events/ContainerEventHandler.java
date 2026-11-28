package mmt.standin.net.minecraft.client.gui.components.events;

import mmt.standin.net.minecraft.client.gui.ComponentPath;
import mmt.standin.net.minecraft.client.gui.navigation.FocusNavigationEvent;

/**
 * 1.19.4's ContainerEventHandler.nextFocusPath and getCurrentFocusPath on 1.19.3, for a port back: the same as the
 * GuiEventListener stand-in's (which reads both releases), a container's own first.
 */
public final class ContainerEventHandler {

    private ContainerEventHandler() {
    }

    public static ComponentPath nextFocusPath(net.minecraft.client.gui.components.events.ContainerEventHandler self, FocusNavigationEvent event) {
        return GuiEventListener.nextFocusPath(self, event);
    }

    public static ComponentPath getCurrentFocusPath(net.minecraft.client.gui.components.events.ContainerEventHandler self) {
        return GuiEventListener.getCurrentFocusPath(self);
    }
}
