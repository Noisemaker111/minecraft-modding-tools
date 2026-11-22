package mmt.standin.net.minecraft.client.gui.components;

/**
 * 1.21.3's AbstractSelectionList on 1.21.4.
 *
 * Read from both releases: 1.21.3's setRenderHeader(render, height) set whether the list draws its header and the
 * header's height (0 when it draws none), which the rows are offset by. 1.21.4 dropped the setter and sets the same two
 * fields (renderHeader, headerHeight) from a constructor that takes the header's height; the fields and every use of
 * them are unchanged. Setting them is the 1.21.3 setter.
 * 1.21.3's updateScrollingState(x, y, button) started a scrollbar drag when the left button went down over the
 * scrollbar's column; 1.21.4 moved it to AbstractScrollArea.updateScrolling(x, y, button), which makes the same test
 * (a valid click button, x within the scrollbar's 6 pixels) and also requires the scrollbar to show and y to be in the
 * list, and answers whether it started.
 *
 * Agrees with NeoForge's 1.21.3 -> 1.21.4 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.4/index.md, CC BY 4.0): "setRenderHeader is now bundled into a new constructor with an extra integer", "updateScrollingState -> AbstractScrollArea#updateScrolling".
 */
public final class AbstractSelectionList {

    private AbstractSelectionList() {}

    public static void setRenderHeader(net.minecraft.client.gui.components.AbstractSelectionList self, boolean render, int height) {
        self.renderHeader = render;
        self.headerHeight = render ? height : 0;
    }

    public static void updateScrollingState(net.minecraft.client.gui.components.AbstractSelectionList self, double x, double y, int button) {
        self.updateScrolling(x, y, button);
    }
}
