package mmt.standin.net.minecraft.client.gui.navigation;

/**
 * 1.19.4's ScreenAxis on 1.19.3: the class itself, which 1.19.3 does not have.
 *
 * Read from both releases: 1.19.4 added it with keyboard navigation (net.minecraft.client.gui.navigation); 1.19.3 has
 * no navigation package. Its code here is 1.19.4's as it reads: it uses nothing 1.19.3 lacks but the other stand-ins of
 * that package.
 */
public enum ScreenAxis {
    HORIZONTAL,
    VERTICAL;

    public ScreenAxis orthogonal() {
        return this == HORIZONTAL ? VERTICAL : HORIZONTAL;
    }

    public ScreenDirection getPositive() {
        return this == HORIZONTAL ? ScreenDirection.RIGHT : ScreenDirection.DOWN;
    }

    public ScreenDirection getNegative() {
        return this == HORIZONTAL ? ScreenDirection.LEFT : ScreenDirection.UP;
    }

    public ScreenDirection getDirection(boolean positive) {
        return positive ? getPositive() : getNegative();
    }
}
