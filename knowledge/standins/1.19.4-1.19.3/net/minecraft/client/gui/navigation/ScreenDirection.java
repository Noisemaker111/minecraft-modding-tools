package mmt.standin.net.minecraft.client.gui.navigation;

import it.unimi.dsi.fastutil.ints.IntComparator;

/**
 * 1.19.4's ScreenDirection on 1.19.3: the class itself, which 1.19.3 does not have.
 *
 * Read from both releases: 1.19.4 added it with keyboard navigation (net.minecraft.client.gui.navigation); 1.19.3 has
 * no navigation package. Its code here is 1.19.4's as it reads: it uses nothing 1.19.3 lacks but the other stand-ins of
 * that package.
 */
public enum ScreenDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT;

    private final IntComparator coordinateValueComparator = new IntComparator() {
        @Override
        public int compare(int a, int b) {
            return a == b ? 0 : (isBefore(a, b) ? -1 : 1);
        }

        @Override
        public int compare(Integer a, Integer b) {
            return compare(a.intValue(), b.intValue());
        }

        @Override
        public int compare(Object a, Object b) {
            return compare(((Integer) a).intValue(), ((Integer) b).intValue());
        }
    };

    public ScreenAxis getAxis() {
        return this == UP || this == DOWN ? ScreenAxis.VERTICAL : ScreenAxis.HORIZONTAL;
    }

    public ScreenDirection getOpposite() {
        if (this == UP) {
            return DOWN;
        }
        if (this == DOWN) {
            return UP;
        }
        return this == LEFT ? RIGHT : LEFT;
    }

    public boolean isPositive() {
        return this == DOWN || this == RIGHT;
    }

    public boolean isAfter(int a, int b) {
        return isPositive() ? a > b : b > a;
    }

    public boolean isBefore(int a, int b) {
        return isPositive() ? a < b : b < a;
    }

    public IntComparator coordinateValueComparator() {
        return coordinateValueComparator;
    }
}
