package mmt.standin.net.minecraft.client.gui.components;

/**
 * 1.20.2's AbstractSelectionList on 1.20.3.
 *
 * Read from both releases: 1.20.2's AbstractSelectionList kept its area as four fields, x0 and x1 (left and right) and
 * y0 and y1 (top and bottom), each set on its own, and updateSize(width, height, y0, y1) set the width, the screen's
 * height, the top and bottom, and the full width from x 0. 1.20.3's list is an AbstractWidget: its area is the widget's
 * x, y, width and height (getX(), getRight(), getY(), getBottom(); setX, setY, setWidth, setHeight, setRectangle). So each
 * old field reads the widget's edge, setting one edge keeps the opposite edge where it was, as the separate fields did, and
 * updateSize sets the rectangle from x 0, y0, the width and y1 - y0.
 *
 * NeoForge's primers begin at 1.20.4 -> 1.20.5, and Fabric's 1.20.3 post (https://fabricmc.net/2023/11/30/1203.html) says
 * only "EntryListWidget no longer takes the bottom coordinate in the constructor. It also actually became a Widget."
 */
public final class AbstractSelectionList {

    private AbstractSelectionList() {}

    public static int x0(net.minecraft.client.gui.components.AbstractSelectionList self) {
        return self.getX();
    }

    public static void x0(net.minecraft.client.gui.components.AbstractSelectionList self, int x0) {
        int right = self.getRight();
        self.setX(x0);
        self.setWidth(right - x0);
    }

    public static int x1(net.minecraft.client.gui.components.AbstractSelectionList self) {
        return self.getRight();
    }

    public static void x1(net.minecraft.client.gui.components.AbstractSelectionList self, int x1) {
        self.setWidth(x1 - self.getX());
    }

    public static int y0(net.minecraft.client.gui.components.AbstractSelectionList self) {
        return self.getY();
    }

    public static void y0(net.minecraft.client.gui.components.AbstractSelectionList self, int y0) {
        int bottom = self.getBottom();
        self.setY(y0);
        self.setHeight(bottom - y0);
    }

    public static int y1(net.minecraft.client.gui.components.AbstractSelectionList self) {
        return self.getBottom();
    }

    public static void y1(net.minecraft.client.gui.components.AbstractSelectionList self, int y1) {
        self.setHeight(y1 - self.getY());
    }

    public static void updateSize(net.minecraft.client.gui.components.AbstractSelectionList self, int width, int height, int y0, int y1) {
        self.setRectangle(width, y1 - y0, 0, y0);
    }
}
