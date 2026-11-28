package mmt.standin.net.minecraft.client.gui.components.events;

/**
 * 1.20.1's ContainerEventHandler.mouseScrolled on 1.20.2.
 *
 * Read from both releases: 1.20.1's ContainerEventHandler.mouseScrolled(x, y, delta) passed one vertical scroll amount to
 * the child under the mouse. 1.20.2's is mouseScrolled(x, y, scrollX, scrollY), MouseHandler.onScroll passing the
 * horizontal and the vertical amount, the vertical one being 1.20.1's delta. A call with no horizontal scroll is the old
 * call, as for GuiEventListener's.
 *
 * Agrees with Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html): "`onMouseScrolled` method now takes both
 * horizontal and vertical scroll amount."
 */
public final class ContainerEventHandler {

    private ContainerEventHandler() {}

    public static boolean mouseScrolled(net.minecraft.client.gui.components.events.ContainerEventHandler self, double x, double y, double delta) {
        return self.mouseScrolled(x, y, 0.0, delta);
    }
}
