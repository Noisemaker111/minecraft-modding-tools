package mmt.standin.net.minecraft.client.gui.components;

/**
 * 1.21.3's AbstractWidget on 1.21.4.
 *
 * Read from both releases: 1.21.3's clicked(x, y) was whether the widget is active and visible and (x, y) is inside it
 * (getX() <= x < getX() + getWidth(), the same for y). 1.21.4 dropped it for isMouseOver(x, y), which is the same test
 * (getRight() and getBottom() being getX() + getWidth() and getY() + getHeight()). The old test is written out here, so
 * a widget that overrides isMouseOver does not change what clicked answered.
 *
 * Agrees with NeoForge's 1.21.3 -> 1.21.4 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.4/index.md, CC BY 4.0): "AbstractWidget#clicked -> isMouseOver, already exists".
 */
public final class AbstractWidget {

    private AbstractWidget() {}

    public static boolean clicked(net.minecraft.client.gui.components.AbstractWidget self, double x, double y) {
        return self.active && self.visible && x >= self.getX() && y >= self.getY() && x < self.getX() + self.getWidth() && y < self.getY() + self.getHeight();
    }
}
