package mmt.standin.net.minecraft.client.gui.components;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * 1.21.11's ChatComponent on 26.1.
 *
 * Read from both releases: 1.21.11's captureClickableText(collector, screenHeight, ticks, chatting) laid the chat out for
 * its clickable text, every line fully visible when the player was chatting and fading by age when not. 26.1 takes a
 * ChatComponent.DisplayMode in place of the boolean: the game's chat screen passes FOREGROUND, or FOREGROUND_RESTRICTED
 * when the player's chat abilities have any restriction (ChatScreen.init: chatAbilities().hasAnyRestrictions()), and the
 * in-game overlay passes BACKGROUND. So chatting is the mode the chat screen would pick, and not chatting is BACKGROUND.
 *
 * 1.21.11's addMessage(message) added a system message made on the client to the chat (tagged systemSinglePlayer() or
 * system()). 26.1 makes addMessage private and says where a message came from: addClientSystemMessage(message) for one the
 * client made (GuiMessageSource.SYSTEM_CLIENT), addServerSystemMessage for the server's, addPlayerMessage for a player's.
 * A mod's own message is the client's, so the old method is addClientSystemMessage.
 *
 * Agrees with NeoForge's 1.21.11 -> 26.1 primer (https://github.com/neoforged/.github/blob/main/primers/26.1/index.md, CC BY 4.0): "render, captureClickableText now take in the $DisplayMode instead of a boolean for if the player is chatting" and "addMessage is now private", "Split for use into addClientSystemMessage, addServerSystemMessage, addPlayerMessage".
 */
public final class ChatComponent {

    private ChatComponent() {}

    public static void captureClickableText(net.minecraft.client.gui.components.ChatComponent self, ActiveTextCollector collector, int screenHeight, int ticks, boolean chatting) {
        net.minecraft.client.gui.components.ChatComponent.DisplayMode mode = net.minecraft.client.gui.components.ChatComponent.DisplayMode.BACKGROUND;
        if (chatting) {
            LocalPlayer player = Minecraft.getInstance().player;
            boolean restricted = player != null && player.chatAbilities().hasAnyRestrictions();
            mode = restricted ? net.minecraft.client.gui.components.ChatComponent.DisplayMode.FOREGROUND_RESTRICTED : net.minecraft.client.gui.components.ChatComponent.DisplayMode.FOREGROUND;
        }
        self.captureClickableText(collector, screenHeight, ticks, mode);
    }

    public static void addMessage(net.minecraft.client.gui.components.ChatComponent self, Component message) {
        self.addClientSystemMessage(message);
    }
}
