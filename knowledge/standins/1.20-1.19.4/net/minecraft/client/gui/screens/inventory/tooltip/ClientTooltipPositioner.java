package mmt.standin.net.minecraft.client.gui.screens.inventory.tooltip;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Vector2ic;

/**
 * 1.20's ClientTooltipPositioner.positionTooltip(screenWidth, screenHeight, x, y, width, height) on 1.19.4.
 *
 * Read from both releases: 1.20 passes the screen's size where 1.19.4's positionTooltip(screen, x, y, width, height)
 * takes the screen, and 1.19.4's positioners (DefaultTooltipPositioner, MenuTooltipPositioner) read only its width and
 * height. So the call goes to 1.19.4's with a screen of that size.
 */
public final class ClientTooltipPositioner {

    private ClientTooltipPositioner() {
    }

    public static Vector2ic positionTooltip(net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner self, int screenWidth, int screenHeight, int x, int y,
                                            int width, int height) {
        return self.positionTooltip(new Sized(screenWidth, screenHeight), x, y, width, height);
    }

    /** A screen that is only a size, which is all a 1.19.4 positioner reads of the screen it is given. */
    static final class Sized extends Screen {

        Sized(int width, int height) {
            super(Component.empty());
            this.width = width;
            this.height = height;
        }
    }
}
