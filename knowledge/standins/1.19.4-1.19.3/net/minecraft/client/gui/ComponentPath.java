package mmt.standin.net.minecraft.client.gui;

import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;

/**
 * 1.19.4's ComponentPath on 1.19.3: the class itself, which 1.19.3 does not have (its two kinds are the classes beside
 * it).
 *
 * Read from both releases: 1.19.4 added it with keyboard navigation, the chain of containers down to the listener a
 * navigation step focuses. Its code here is 1.19.4's as it reads.
 */
public interface ComponentPath {

    static ComponentPath leaf(GuiEventListener component) {
        return new ComponentPath$Leaf(component);
    }

    static ComponentPath path(ContainerEventHandler container, ComponentPath child) {
        return child == null ? null : new ComponentPath$Path(container, child);
    }

    static ComponentPath path(GuiEventListener component, ContainerEventHandler... containers) {
        ComponentPath path = leaf(component);
        for (ContainerEventHandler container : containers) {
            path = path(container, path);
        }
        return path;
    }

    GuiEventListener component();

    void applyFocus(boolean focused);
}
