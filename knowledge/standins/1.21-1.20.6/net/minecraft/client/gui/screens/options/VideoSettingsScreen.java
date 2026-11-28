package mmt.standin.net.minecraft.client.gui.screens.options;

/**
 * 1.21's VideoSettingsScreen(Screen, Minecraft, Options) on 1.20.6, for a port back: 1.20.6's screen, which finds the game itself.
 *
 * Read from both releases: 1.21's options screens moved to net.minecraft.client.gui.screens.options and take the game
 * (OptionsSubScreen); 1.20.6's VideoSettingsScreen(Screen, Options) in net.minecraft.client.gui.screens is the same
 * screen, which reads Minecraft from the Screen it is (Minecraft.getInstance() once added).
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "The options screens within `net.minecraft.client.gui.screens` and `net.minecraft.client.gui.screens.controls` have been moved to `net.minecraft.client.gui.screens.options`".
 */
public final class VideoSettingsScreen {

    private VideoSettingsScreen() {}

    public static net.minecraft.client.gui.screens.VideoSettingsScreen init(net.minecraft.client.gui.screens.Screen parent, net.minecraft.client.Minecraft minecraft, net.minecraft.client.Options options) {
        return new net.minecraft.client.gui.screens.VideoSettingsScreen(parent, options);
    }
}
