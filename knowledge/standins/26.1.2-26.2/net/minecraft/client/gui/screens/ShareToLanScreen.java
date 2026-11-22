package mmt.standin.net.minecraft.client.gui.screens;

import net.minecraft.client.gui.screens.MultiplayerOptionsScreen;

/**
 * 26.1.2's ShareToLanScreen, which 26.2 replaced with MultiplayerOptionsScreen.
 *
 * Read from both releases: 26.1.2's pause screen opened ShareToLanScreen from its "Open to LAN" button (PauseScreen:
 * openScreenButton(SHARE_TO_LAN, () -> new ShareToLanScreen(this))). 26.2's opens MultiplayerOptionsScreen from that
 * place (openScreenButton(MULTIPLAYER_OPTIONS, () -> new MultiplayerOptionsScreen(this))), the screen where a world is
 * shared now. So a screen is what 26.1.2 called a ShareToLanScreen when it is a MultiplayerOptionsScreen.
 *
 * Agrees with NeoForge's 26.1.2 -> 26.2 primer (https://github.com/neoforged/.github/blob/main/primers/26.2/index.md, CC BY 4.0): "ShareToLanScreen -> MultiplayerOptionsScreen, not one-to-one".
 */
public final class ShareToLanScreen {

    private ShareToLanScreen() {}

    public static boolean isInstance(Object value) {
        return value instanceof MultiplayerOptionsScreen;
    }
}
