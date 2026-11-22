package mmt.standin.net.minecraft.client.gui.screens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 1.20.1's Screen.renderBackground(graphics) on 1.20.2.
 *
 * Read from both releases: 1.20.1's renderBackground(graphics) drew the translucent gradient over a world, else the dirt
 * background; a screen called it first in its own render. 1.20.2's is renderBackground(graphics, mouseX, mouseY,
 * partialTick), the same drawing (renderTransparentBackground or renderDirtBackground), and Screen.render calls it
 * itself. The old call is the new method at the mouse's place on the screen (as MouseHandler works it out for a screen)
 * and the frame's partial tick, so a screen that overrides it still draws its own.
 *
 * Agrees with Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html): "`renderBackground` method now takes mouse
 * positions and `tickDelta`."
 */
public final class Screen {

    private Screen() {}

    public static void renderBackground(net.minecraft.client.gui.screens.Screen self, GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        int x = (int) (mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth());
        int y = (int) (mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight());
        self.renderBackground(graphics, x, y, mc.getFrameTime());
    }
}
