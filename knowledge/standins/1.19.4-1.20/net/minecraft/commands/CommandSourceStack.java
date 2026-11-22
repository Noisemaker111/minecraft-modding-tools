package mmt.standin.net.minecraft.commands;

import net.minecraft.network.chat.Component;

/**
 * 1.19.4's CommandSourceStack.sendSuccess(message, broadcastToAdmins) on 1.20.
 *
 * Read from both releases: 1.19.4's sendSuccess(component, allowLogging) sent the component to the source when it takes
 * success messages and is not silent, and to the server's operators when allowLogging is set and the source informs
 * them. 1.20's takes a Supplier<Component> and makes the component only when one of those will be sent; the sends are
 * the same. A supplier of the made component is the old call.
 *
 * Fabric's 1.20 post (https://fabricmc.net/2023/05/25/120.html) does not mention sendSuccess, and NeoForge's primers begin at 1.20.2 -> 1.20.3; this is from the game's code alone.
 */
public final class CommandSourceStack {

    private CommandSourceStack() {}

    public static void sendSuccess(net.minecraft.commands.CommandSourceStack self, Component message, boolean allowLogging) {
        self.sendSuccess(() -> message, allowLogging);
    }
}
