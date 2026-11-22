package mmt.standin.net.minecraft.client.player;

import net.minecraft.network.chat.Component;

/**
 * 1.21.11's LocalPlayer on 26.1.
 *
 * Read from both releases: 1.21.11's LocalPlayer.displayClientMessage(message, overlay) handed the message to the chat
 * listener (handleSystemMessage(message, overlay)), into the chat or, when overlay, above the hotbar. 26.1 splits it:
 * LocalPlayer.sendSystemMessage(message) puts it in the chat (handleSystemMessage) and sendOverlayMessage(message) above
 * the hotbar (handleOverlay). So the old method is the one of the two its flag names.
 *
 * Agrees with NeoForge's 1.21.11 -> 26.1 primer (https://github.com/neoforged/.github/blob/main/primers/26.1/index.md, CC BY 4.0): "Player#displayClientMessage split into sendSystemMessage when overlay message was false, and sendOverlayMessage when the overlay message was true".
 */
public final class LocalPlayer {

    private LocalPlayer() {}

    public static void displayClientMessage(net.minecraft.client.player.LocalPlayer self, Component message, boolean overlay) {
        if (overlay) {
            self.sendOverlayMessage(message);
        } else {
            self.sendSystemMessage(message);
        }
    }
}
