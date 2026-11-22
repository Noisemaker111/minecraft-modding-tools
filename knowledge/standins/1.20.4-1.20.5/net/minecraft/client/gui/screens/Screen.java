package mmt.standin.net.minecraft.client.gui.screens;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.20.4's Screen on 1.20.5.
 *
 * Read from both releases: 1.20.4's renderDirtBackground(graphics) tiled the menu background texture
 * (BACKGROUND_LOCATION, textures/gui/options_background.png, the dirt) over the whole screen, 32 pixels a tile, darkened
 * to a quarter. 1.20.5 dropped the dirt texture and draws menus over MENU_BACKGROUND (textures/gui/menu_background.png)
 * with renderMenuBackground, which tiles it the same way through the public renderMenuBackgroundTexture(graphics,
 * texture, x, y, u, v, width, height). So the menu background texture is MENU_BACKGROUND, tiled over the screen as
 * 1.20.5 tiles it.
 *
 * Agrees with NeoForge's 1.20.4 -> 1.20.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.20.5/index.md, CC BY 4.0): "renderDirtBackground -> renderMenuBackground".
 */
public final class Screen {

    private Screen() {}

    public static ResourceLocation BACKGROUND_LOCATION() {
        return net.minecraft.client.gui.screens.Screen.MENU_BACKGROUND;
    }

    public static void renderDirtBackground(net.minecraft.client.gui.screens.Screen self, GuiGraphics graphics) {
        net.minecraft.client.gui.screens.Screen.renderMenuBackgroundTexture(graphics, net.minecraft.client.gui.screens.Screen.MENU_BACKGROUND, 0, 0, 0.0F, 0.0F, self.width, self.height);
    }
}
