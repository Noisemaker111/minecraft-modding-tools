package mmt.standin.net.minecraft.world.entity.player;

import net.minecraft.network.chat.Component;

/**
 * 1.21.11's Player on 26.1.
 *
 * Read from both releases: 1.21.11's Player.displayClientMessage(message, overlay) showed a message to the player, in the
 * chat or, when overlay, above the hotbar (ServerPlayer sent it as a system message with that flag, LocalPlayer handed it
 * to the chat listener). 26.1 splits it in two: sendSystemMessage(message) for the chat and sendOverlayMessage(message)
 * above the hotbar, each written by ServerPlayer (sendSystemMessage(message, false / true)) and LocalPlayer (the chat
 * listener). So the old method is the one of the two its flag names, which the player's own class answers.
 *
 * Agrees with NeoForge's 1.21.11 -> 26.1 primer (https://github.com/neoforged/.github/blob/main/primers/26.1/index.md, CC BY 4.0): "Player#displayClientMessage split into sendSystemMessage when overlay message was false, and sendOverlayMessage when the overlay message was true".
 */
public final class Player {

    private Player() {}

    public static void displayClientMessage(net.minecraft.world.entity.player.Player self, Component message, boolean overlay) {
        if (overlay) {
            self.sendOverlayMessage(message);
        } else {
            self.sendSystemMessage(message);
        }
    }
}
