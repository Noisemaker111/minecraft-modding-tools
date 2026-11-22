package mmt.standin.net.minecraft.client.gui.components;

/**
 * 1.19.3's AbstractWidget.getYImage(hovered) and changeFocus(forward) on 1.19.4.
 *
 * Read from both releases: 1.19.3's getYImage(hovered) picked the row of the widgets texture to draw: 0 inactive, 2
 * hovered, else 1; changeFocus(forward) toggled the widget's focus when it was active and visible and returned whether
 * it is now focused, else returned false. 1.19.4 dropped both: buttons pick their row in getTextureY, and focus is set
 * by the screen's focus path through setFocused(focused) and read with isFocused(). These are 1.19.3's bodies on the
 * widget's public active and visible fields and 1.19.4's setFocused and isFocused.
 *
 * Agrees with Fabric's 1.19.4 post (https://fabricmc.net/2023/03/01/1194.html): "GUI code has received changes to
 * support new keyboard navigation. After each keyboard navigation, the game recalculates the focused element".
 */
public final class AbstractWidget {

    private AbstractWidget() {}

    public static int getYImage(net.minecraft.client.gui.components.AbstractWidget self, boolean hovered) {
        if (!self.active) {
            return 0;
        }
        return hovered ? 2 : 1;
    }

    public static boolean changeFocus(net.minecraft.client.gui.components.AbstractWidget self, boolean forward) {
        if (!self.active || !self.visible) {
            return false;
        }
        self.setFocused(!self.isFocused());
        return self.isFocused();
    }
}
