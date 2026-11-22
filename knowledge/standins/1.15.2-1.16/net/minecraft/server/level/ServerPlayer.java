package mmt.standin.net.minecraft.server.level;

import net.minecraft.Util;
import net.minecraft.network.chat.Component;

/**
 * 1.15.2's ServerPlayer.sendMessage(Component) on 1.16.
 *
 * Read from both releases: 1.15.2's sendMessage(Component) sent the message as a SYSTEM chat message. 1.16's
 * sendMessage(Component, UUID) does the same with the sender's UUID, Util.NIL_UUID for none, which 1.16's own system
 * messages pass. This passes Util.NIL_UUID.
 *
 * Fabric's 1.16 post (https://fabricmc.net/2020/06/23/116.html) does not mention it; this is from the game's code alone.
 */
public final class ServerPlayer {

    private ServerPlayer() {}

    public static void sendMessage(net.minecraft.server.level.ServerPlayer self, Component message) {
        self.sendMessage(message, Util.NIL_UUID);
    }
}
