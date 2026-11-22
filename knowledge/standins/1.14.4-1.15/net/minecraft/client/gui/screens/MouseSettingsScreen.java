package mmt.standin.net.minecraft.client.gui.screens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 1.14.4's new MouseSettingsScreen(Screen) on 1.15.
 *
 * Read from both releases: 1.14.4's took the screen to go back to and read the options from the game; 1.15's is an
 * OptionsSubScreen that takes the options too, and 1.15's own ControlsScreen passes the game's (this.options, which is
 * Minecraft.getInstance().options). This passes the same.
 *
 * Fabric's 1.15 post (https://fabricmc.net/2019/12/10/115.html) does not mention it; this is from the game's code alone.
 */
public final class MouseSettingsScreen {

    private MouseSettingsScreen() {}

    public static net.minecraft.client.gui.screens.MouseSettingsScreen init(Screen parent) {
        return new net.minecraft.client.gui.screens.MouseSettingsScreen(parent, Minecraft.getInstance().options);
    }
}
