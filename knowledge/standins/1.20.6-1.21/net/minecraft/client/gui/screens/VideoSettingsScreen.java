package mmt.standin.net.minecraft.client.gui.screens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;

/**
 * 1.20.6's VideoSettingsScreen on 1.21.
 *
 * Read from both releases: 1.20.6's new VideoSettingsScreen(parent, options) took the game from the parent screen
 * (parent.minecraft) for its GPU warning list. 1.21 moved it to net.minecraft.client.gui.screens.options and takes the
 * game itself: new VideoSettingsScreen(parent, minecraft, options); there is one game, Minecraft.getInstance().
 *
 * Game files only: the primer does not list it.
 */
public final class VideoSettingsScreen {

    private VideoSettingsScreen() {}

    public static net.minecraft.client.gui.screens.options.VideoSettingsScreen init(Screen parent, Options options) {
        return new net.minecraft.client.gui.screens.options.VideoSettingsScreen(parent, Minecraft.getInstance(), options);
    }
}
