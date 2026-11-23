package mmt.standin.net.minecraft.client.player;

import net.minecraft.network.chat.Component;

/**
 * 1.19's LocalPlayer chat and command sending on 1.19.1.
 *
 * Read from both releases: 1.19's chat(message, preview) signed the message with the player's MessageSigner and sent it
 * (sendChat), and command(command, preview) did the same for a command and its signable arguments (sendCommand);
 * chat(message) and command(command) passed no preview (null). 1.19.1 reworked chat signing (message chains, last seen
 * messages) and renamed the two senders chatSigned(message, preview) and commandSigned(command, preview), which build
 * and sign the same packets; ChatScreen.handleChatInput calls them where 1.19's called chat and command. So each old
 * method is its renamed sender with the same arguments.
 *
 * NeoForge's primers have no 1.19.1 page; this is from the game's code alone.
 */
public final class LocalPlayer {

    private LocalPlayer() {}

    public static void chat(net.minecraft.client.player.LocalPlayer self, String message) {
        self.chatSigned(message, null);
    }

    public static void chat(net.minecraft.client.player.LocalPlayer self, String message, Component preview) {
        self.chatSigned(message, preview);
    }

    public static void command(net.minecraft.client.player.LocalPlayer self, String command) {
        self.commandSigned(command, null);
    }

    public static void command(net.minecraft.client.player.LocalPlayer self, String command, Component preview) {
        self.commandSigned(command, preview);
    }
}
