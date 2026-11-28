package mmt.standin.net.minecraft.client.gui.components.events;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import mmt.standin.net.minecraft.client.gui.ComponentPath;
import mmt.standin.net.minecraft.client.gui.navigation.FocusNavigationEvent;
import mmt.standin.net.minecraft.client.gui.navigation.FocusNavigationEvent$ArrowNavigation;
import mmt.standin.net.minecraft.client.gui.navigation.FocusNavigationEvent$TabNavigation;
import mmt.standin.net.minecraft.client.gui.navigation.ScreenAxis;
import mmt.standin.net.minecraft.client.gui.navigation.ScreenDirection;
import mmt.standin.net.minecraft.client.gui.navigation.ScreenPosition;
import mmt.standin.net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.screens.Screen;

/**
 * 1.19.4's GuiEventListener focus and navigation methods on 1.19.3, for a port back: nextFocusPath, getCurrentFocusPath,
 * getRectangle and isFocused, and ContainerEventHandler's nextFocusPath and getCurrentFocusPath (the
 * stand-in for that class calls these).
 *
 * Read from both releases: 1.19.4 added keyboard navigation. Its GuiEventListener defaults are no next path,
 * getCurrentFocusPath a leaf of the listener when it is focused, an empty rectangle and tab group 0; AbstractWidget
 * answers its own bounds and a leaf of itself where it is active, visible and not focused; Screen answers the whole
 * screen; a ContainerEventHandler first asks its focused child, then steps by tab order or towards the arrow's direction
 * among its children (1.19.4's code as it reads). 1.19.3 has none of these methods: its focus moves by changeFocus.
 *
 * A listener written for 1.19.4 or later declares its own of these: a mod's call of one on any listener runs the
 * listener's own where its class has one, as the JVM's dispatch would in 1.19.4, and 1.19.4's default otherwise; the
 * same call made from inside that listener's own (a super call) runs the default.
 *
 * Not the same: isFocused on a 1.19.3 container (which keeps no focus flag of its own, unlike 1.19.4's
 * AbstractContainerEventHandler) is whether it has a focused child. 1.19.3 never asks for a navigation step itself, so
 * these run only where a mod does.
 */
public final class GuiEventListener {

    private GuiEventListener() {
    }

    private static final Object NONE = new Object();

    private static final ThreadLocal<Map<Object, Set<String>>> RUNNING = ThreadLocal.withInitial(IdentityHashMap::new);

    /** The listener's own public method of this name, parameters and result, run unless it is running for it already. */
    private static Object own(Object self, String name, Class<?> result, Class<?>[] params, Object... args) {
        Method m;
        try {
            m = self.getClass().getMethod(name, params);
        } catch (NoSuchMethodException none) {
            return NONE;
        }
        if (m.getReturnType() != result) {
            return NONE;
        }
        Set<String> running = RUNNING.get().computeIfAbsent(self, k -> new HashSet<>());
        if (!running.add(name)) {
            return NONE;
        }
        try {
            m.setAccessible(true);
            return m.invoke(self, args);
        } catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof RuntimeException r) {
                throw r;
            }
            if (failed.getCause() instanceof Error e) {
                throw e;
            }
            throw new RuntimeException(failed.getCause());
        } catch (IllegalAccessException closed) {
            throw new IllegalStateException(closed);
        } finally {
            running.remove(name);
            if (running.isEmpty()) {
                RUNNING.get().remove(self);
            }
        }
    }

    public static ComponentPath nextFocusPath(net.minecraft.client.gui.components.events.GuiEventListener self, FocusNavigationEvent event) {
        Object own = own(self, "nextFocusPath", ComponentPath.class, new Class<?>[] { FocusNavigationEvent.class }, event);
        if (own != NONE) {
            return (ComponentPath) own;
        }
        if (self instanceof ContainerEventHandler container) {
            return containerNext(container, event);
        }
        if (self instanceof AbstractWidget widget) {
            return !widget.active || !widget.visible || widget.isFocused() ? null : ComponentPath.leaf(widget);
        }
        return null;
    }

    public static ComponentPath getCurrentFocusPath(net.minecraft.client.gui.components.events.GuiEventListener self) {
        Object own = own(self, "getCurrentFocusPath", ComponentPath.class, new Class<?>[0]);
        if (own != NONE) {
            return (ComponentPath) own;
        }
        if (self instanceof ContainerEventHandler container) {
            net.minecraft.client.gui.components.events.GuiEventListener focused = container.getFocused();
            return focused != null ? ComponentPath.path(container, getCurrentFocusPath(focused)) : null;
        }
        return isFocused(self) ? ComponentPath.leaf(self) : null;
    }

    public static ScreenRectangle getRectangle(net.minecraft.client.gui.components.events.GuiEventListener self) {
        Object own = own(self, "getRectangle", ScreenRectangle.class, new Class<?>[0]);
        if (own != NONE) {
            return (ScreenRectangle) own;
        }
        if (self instanceof AbstractWidget widget) {
            return new ScreenRectangle(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight());
        }
        if (self instanceof Screen screen) {
            return new ScreenRectangle(0, 0, screen.width, screen.height);
        }
        return ScreenRectangle.empty();
    }

    public static boolean isFocused(net.minecraft.client.gui.components.events.GuiEventListener self) {
        Object own = own(self, "isFocused", boolean.class, new Class<?>[0]);
        if (own != NONE) {
            return (Boolean) own;
        }
        return self instanceof ContainerEventHandler container && container.getFocused() != null;
    }

    private static int tabGroup(net.minecraft.client.gui.components.events.GuiEventListener self) {
        Object own = own(self, "getTabOrderGroup", int.class, new Class<?>[0]);
        return own != NONE ? (Integer) own : 0;
    }

    private static ComponentPath containerNext(ContainerEventHandler self, FocusNavigationEvent event) {
        net.minecraft.client.gui.components.events.GuiEventListener focused = self.getFocused();
        if (focused != null) {
            ComponentPath path = nextFocusPath(focused, event);
            if (path != null) {
                return ComponentPath.path(self, path);
            }
        }
        if (event instanceof FocusNavigationEvent$TabNavigation tab) {
            return tab(self, tab);
        }
        return event instanceof FocusNavigationEvent$ArrowNavigation arrow ? arrow(self, arrow) : null;
    }

    private static ComponentPath tab(ContainerEventHandler self, FocusNavigationEvent$TabNavigation event) {
        boolean forward = event.forward();
        net.minecraft.client.gui.components.events.GuiEventListener focused = self.getFocused();
        List<net.minecraft.client.gui.components.events.GuiEventListener> children = new ArrayList<>();
        for (Object child : self.children()) {
            children.add((net.minecraft.client.gui.components.events.GuiEventListener) child);
        }
        children.sort(Comparator.comparingInt(GuiEventListener::tabGroup));
        int at = children.indexOf(focused);
        int start = focused != null && at >= 0 ? at + (forward ? 1 : 0) : forward ? 0 : children.size();
        ListIterator<net.minecraft.client.gui.components.events.GuiEventListener> it = children.listIterator(start);
        while (forward ? it.hasNext() : it.hasPrevious()) {
            ComponentPath path = nextFocusPath(forward ? it.next() : it.previous(), event);
            if (path != null) {
                return ComponentPath.path(self, path);
            }
        }
        return null;
    }

    private static ComponentPath arrow(ContainerEventHandler self, FocusNavigationEvent$ArrowNavigation event) {
        net.minecraft.client.gui.components.events.GuiEventListener focused = self.getFocused();
        if (focused == null) {
            ScreenDirection direction = event.direction();
            ScreenRectangle from = getRectangle(self).getBorder(direction.getOpposite());
            return ComponentPath.path(self, inDirection(self, from, direction, null, event));
        }
        return ComponentPath.path(self, inDirection(self, getRectangle(focused), event.direction(), focused, event));
    }

    private static ComponentPath inDirection(ContainerEventHandler self, ScreenRectangle from, ScreenDirection direction, net.minecraft.client.gui.components.events.GuiEventListener skip, FocusNavigationEvent event) {
        ScreenAxis across = direction.getAxis().orthogonal();
        ScreenDirection acrossPositive = across.getPositive();
        int edge = from.getBoundInDirection(direction.getOpposite());
        List<net.minecraft.client.gui.components.events.GuiEventListener> found = new ArrayList<>();
        for (Object o : self.children()) {
            net.minecraft.client.gui.components.events.GuiEventListener child = (net.minecraft.client.gui.components.events.GuiEventListener) o;
            if (child == skip) {
                continue;
            }
            ScreenRectangle r = getRectangle(child);
            if (r.overlapsInAxis(from, across)) {
                int near = r.getBoundInDirection(direction.getOpposite());
                if (direction.isAfter(near, edge) || near == edge && direction.isAfter(r.getBoundInDirection(direction), from.getBoundInDirection(direction))) {
                    found.add(child);
                }
            }
        }
        found.sort((a, b) -> {
            int c = direction.coordinateValueComparator().compare(getRectangle(a).getBoundInDirection(direction.getOpposite()), getRectangle(b).getBoundInDirection(direction.getOpposite()));
            return c != 0 ? c : acrossPositive.coordinateValueComparator().compare(getRectangle(a).getBoundInDirection(acrossPositive.getOpposite()), getRectangle(b).getBoundInDirection(acrossPositive.getOpposite()));
        });
        for (net.minecraft.client.gui.components.events.GuiEventListener child : found) {
            ComponentPath path = nextFocusPath(child, event);
            if (path != null) {
                return path;
            }
        }
        return vaguely(self, from, direction, skip, event);
    }

    private static ComponentPath vaguely(ContainerEventHandler self, ScreenRectangle from, ScreenDirection direction, net.minecraft.client.gui.components.events.GuiEventListener skip, FocusNavigationEvent event) {
        ScreenAxis axis = direction.getAxis();
        ScreenAxis across = axis.orthogonal();
        List<Object[]> found = new ArrayList<>();
        ScreenPosition start = ScreenPosition.of(axis, from.getBoundInDirection(direction), from.getCenterInAxis(across));
        for (Object o : self.children()) {
            net.minecraft.client.gui.components.events.GuiEventListener child = (net.minecraft.client.gui.components.events.GuiEventListener) o;
            if (child == skip) {
                continue;
            }
            ScreenRectangle r = getRectangle(child);
            ScreenPosition p = ScreenPosition.of(axis, r.getBoundInDirection(direction.getOpposite()), r.getCenterInAxis(across));
            if (direction.isAfter(p.getCoordinate(axis), start.getCoordinate(axis))) {
                long dx = (long) start.x() - p.x();
                long dy = (long) start.y() - p.y();
                found.add(new Object[] { child, dx * dx + dy * dy });
            }
        }
        found.sort(Comparator.comparingDouble(e -> (double) (Long) e[1]));
        for (Object[] e : found) {
            ComponentPath path = nextFocusPath((net.minecraft.client.gui.components.events.GuiEventListener) e[0], event);
            if (path != null) {
                return path;
            }
        }
        return null;
    }
}
