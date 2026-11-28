package mmt.standin.net.minecraft.client.gui;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraft.client.gui.components.events.GuiEventListener;

/**
 * 1.19.4's ComponentPath.Leaf on 1.19.3: the class itself, which 1.19.3 does not have.
 *
 * Read from both releases: 1.19.4's applyFocus calls the listener's setFocused(boolean), which 1.19.4 made a method of
 * every GuiEventListener. 1.19.3's GuiEventListener has none; its AbstractWidget has a protected setFocused(boolean), and
 * listeners written for 1.19.4 or later declare their own. So it calls the listener's own setFocused(boolean) where its
 * class has one, and does nothing for a 1.19.3 listener without one (1.19.4 has none such).
 */
public record ComponentPath$Leaf(GuiEventListener component) implements ComponentPath {

    @Override
    public void applyFocus(boolean focused) {
        for (Class<?> c = component.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method set = c.getDeclaredMethod("setFocused", boolean.class);
                set.setAccessible(true);
                set.invoke(component, focused);
                return;
            } catch (NoSuchMethodException next) {
                continue;
            } catch (InvocationTargetException failed) {
                throw failed.getCause() instanceof RuntimeException r ? r : new RuntimeException(failed.getCause());
            } catch (IllegalAccessException closed) {
                throw new IllegalStateException(closed);
            }
        }
    }
}
