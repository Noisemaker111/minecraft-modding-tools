package mmt.standin.net.minecraft.client.gui.components;

/**
 * 1.20.1's AbstractSelectionList.mouseScrolled and setRenderSelection on 1.20.2.
 *
 * Read from both releases: 1.20.1's AbstractSelectionList.mouseScrolled(x, y, delta) moved the list by delta times half an
 * item's height. 1.20.2's mouseScrolled(x, y, scrollX, scrollY) moves it the same way by scrollY, the vertical amount
 * MouseHandler.onScroll passes, which is 1.20.1's delta. A call with no horizontal scroll is the old call.
 *
 * 1.20.1's setRenderSelection(flag) kept whether renderListItems outlined the selected entry. 1.20.2 dropped the flag:
 * renderListItems outlines the selected entry whenever there is one. The game's one caller, RealmsBackupInfoScreen's
 * BackupInfoList, called setRenderSelection(false) in its constructor in 1.20.1 and makes no call in its place in 1.20.2,
 * so the old call is no call.
 *
 * Agrees with Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html): "`onMouseScrolled` method now takes both
 * horizontal and vertical scroll amount." The post does not mention setRenderSelection.
 */
public final class AbstractSelectionList {

    private AbstractSelectionList() {}

    public static boolean mouseScrolled(net.minecraft.client.gui.components.AbstractSelectionList self, double x, double y, double delta) {
        return self.mouseScrolled(x, y, 0.0, delta);
    }

    public static void setRenderSelection(net.minecraft.client.gui.components.AbstractSelectionList self, boolean flag) {
    }
}
