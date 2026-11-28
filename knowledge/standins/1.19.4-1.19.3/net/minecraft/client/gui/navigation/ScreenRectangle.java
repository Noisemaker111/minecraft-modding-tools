package mmt.standin.net.minecraft.client.gui.navigation;

/**
 * 1.19.4's ScreenRectangle on 1.19.3: the class itself, which 1.19.3 does not have.
 *
 * Read from both releases: 1.19.4 added it with keyboard navigation (net.minecraft.client.gui.navigation); 1.19.3 has
 * no navigation package. Its code here is 1.19.4's as it reads: it uses nothing 1.19.3 lacks but the other stand-ins of
 * that package.
 */
public record ScreenRectangle(ScreenPosition position, int width, int height) {

    private static final ScreenRectangle EMPTY = new ScreenRectangle(0, 0, 0, 0);

    public ScreenRectangle(int x, int y, int width, int height) {
        this(new ScreenPosition(x, y), width, height);
    }

    public static ScreenRectangle empty() {
        return EMPTY;
    }

    public static ScreenRectangle of(ScreenAxis axis, int primary, int secondary, int primaryLength, int secondaryLength) {
        return axis == ScreenAxis.HORIZONTAL
            ? new ScreenRectangle(primary, secondary, primaryLength, secondaryLength)
            : new ScreenRectangle(secondary, primary, secondaryLength, primaryLength);
    }

    public ScreenRectangle step(ScreenDirection direction) {
        return new ScreenRectangle(position.step(direction), width, height);
    }

    public int getLength(ScreenAxis axis) {
        return axis == ScreenAxis.HORIZONTAL ? width : height;
    }

    public int getBoundInDirection(ScreenDirection direction) {
        ScreenAxis axis = direction.getAxis();
        return direction.isPositive() ? position.getCoordinate(axis) + getLength(axis) - 1 : position.getCoordinate(axis);
    }

    public ScreenRectangle getBorder(ScreenDirection direction) {
        int bound = getBoundInDirection(direction);
        ScreenAxis across = direction.getAxis().orthogonal();
        int start = getBoundInDirection(across.getNegative());
        int length = getLength(across);
        return of(direction.getAxis(), bound, start, 1, length).step(direction);
    }

    public boolean overlaps(ScreenRectangle other) {
        return overlapsInAxis(other, ScreenAxis.HORIZONTAL) && overlapsInAxis(other, ScreenAxis.VERTICAL);
    }

    public boolean overlapsInAxis(ScreenRectangle other, ScreenAxis axis) {
        int start = getBoundInDirection(axis.getNegative());
        int otherStart = other.getBoundInDirection(axis.getNegative());
        int end = getBoundInDirection(axis.getPositive());
        int otherEnd = other.getBoundInDirection(axis.getPositive());
        return Math.max(start, otherStart) <= Math.min(end, otherEnd);
    }

    public int getCenterInAxis(ScreenAxis axis) {
        return (getBoundInDirection(axis.getPositive()) + getBoundInDirection(axis.getNegative())) / 2;
    }

    public ScreenRectangle intersection(ScreenRectangle other) {
        int left = Math.max(left(), other.left());
        int top = Math.max(top(), other.top());
        int right = Math.min(right(), other.right());
        int bottom = Math.min(bottom(), other.bottom());
        return left < right && top < bottom ? new ScreenRectangle(left, top, right - left, bottom - top) : null;
    }

    public int top() {
        return position.y();
    }

    public int bottom() {
        return position.y() + height;
    }

    public int left() {
        return position.x();
    }

    public int right() {
        return position.x() + width;
    }
}
