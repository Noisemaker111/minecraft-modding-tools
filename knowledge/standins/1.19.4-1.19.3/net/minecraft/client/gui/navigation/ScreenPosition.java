package mmt.standin.net.minecraft.client.gui.navigation;

/**
 * 1.19.4's ScreenPosition on 1.19.3: the class itself, which 1.19.3 does not have.
 *
 * Read from both releases: 1.19.4 added it with keyboard navigation (net.minecraft.client.gui.navigation); 1.19.3 has
 * no navigation package. Its code here is 1.19.4's as it reads: it uses nothing 1.19.3 lacks but the other stand-ins of
 * that package.
 */
public record ScreenPosition(int x, int y) {

    public static ScreenPosition of(ScreenAxis axis, int primary, int secondary) {
        return axis == ScreenAxis.HORIZONTAL ? new ScreenPosition(primary, secondary) : new ScreenPosition(secondary, primary);
    }

    public ScreenPosition step(ScreenDirection direction) {
        if (direction == ScreenDirection.DOWN) {
            return new ScreenPosition(x, y + 1);
        }
        if (direction == ScreenDirection.UP) {
            return new ScreenPosition(x, y - 1);
        }
        return direction == ScreenDirection.LEFT ? new ScreenPosition(x - 1, y) : new ScreenPosition(x + 1, y);
    }

    public int getCoordinate(ScreenAxis axis) {
        return axis == ScreenAxis.HORIZONTAL ? x : y;
    }
}
