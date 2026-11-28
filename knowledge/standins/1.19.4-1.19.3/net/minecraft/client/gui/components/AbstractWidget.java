package mmt.standin.net.minecraft.client.gui.components;

import mmt.standin.net.minecraft.client.gui.ComponentPath;
import mmt.standin.net.minecraft.client.gui.components.events.GuiEventListener;
import mmt.standin.net.minecraft.client.gui.navigation.FocusNavigationEvent;
import mmt.standin.net.minecraft.client.gui.navigation.ScreenRectangle;

/**
 * 1.19.4's AbstractWidget.nextFocusPath and getRectangle on 1.19.3, for a port back: the same as the GuiEventListener
 * stand-in's (which reads both releases), a widget's own first.
 */
public final class AbstractWidget {

    private AbstractWidget() {
    }

    public static ComponentPath nextFocusPath(net.minecraft.client.gui.components.AbstractWidget self, FocusNavigationEvent event) {
        return GuiEventListener.nextFocusPath(self, event);
    }

    public static ScreenRectangle getRectangle(net.minecraft.client.gui.components.AbstractWidget self) {
        return GuiEventListener.getRectangle(self);
    }
}
