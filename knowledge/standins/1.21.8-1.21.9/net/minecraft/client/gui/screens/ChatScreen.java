package mmt.standin.net.minecraft.client.gui.screens;

/**
 * 1.21.8's ChatScreen on 1.21.9.
 *
 * Read from both releases: 1.21.8's ChatScreen(initial) opened chat with initial in the box. 1.21.9 keeps a draft of
 * unsent chat and takes whether the text is that draft (ChatScreen(initial, isDraft)): ChatComponent.createScreen
 * passes true only when it restores the saved draft, and false for a screen opened with given text (a chat method's
 * prefix, "/" for commands). An old call opens chat with given text.
 *
 * Agrees with NeoForge's 1.21.8 -> 1.21.9 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.9/index.md, CC BY 4.0): "ChatScreen now takes in a boolean representing whether the message is a draft".
 */
public final class ChatScreen {

    private ChatScreen() {}

    public static net.minecraft.client.gui.screens.ChatScreen init(String initial) {
        return new net.minecraft.client.gui.screens.ChatScreen(initial, false);
    }
}
