package mmt.standin.net.minecraft.client.multiplayer;

import net.minecraft.network.chat.SignableCommand;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;

/**
 * 1.21.5's ClientPacketListener.sendUnsignedCommand on 1.21.6.
 *
 * Read from both releases: 1.21.5's sendUnsignedCommand(command) parsed the command with the listener's dispatcher and
 * suggestions provider and, when no argument of it needed a signature (SignableCommand.hasSignableArguments), sent it as a
 * ServerboundChatCommandPacket and answered true; otherwise it sent nothing and answered false. 1.21.6 removed the method;
 * its own sendCommand and sendUnattendedCommand make the same check from getCommands().parse(command,
 * getSuggestionsProvider()) and send the same packet through send(packet), all still public.
 *
 * Agrees with NeoForge's 1.21.5 -> 1.21.6 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.6/index.md, CC BY 4.0):
 * "ClientPacketListener: getCommands now returns a ClientSuggestionProvider generic", "sendUnattendedCommand now takes in a
 * Screen instead of a boolean".
 */
public final class ClientPacketListener {

    private ClientPacketListener() {}

    public static boolean sendUnsignedCommand(net.minecraft.client.multiplayer.ClientPacketListener self, String command) {
        if (!SignableCommand.hasSignableArguments(self.getCommands().parse(command, self.getSuggestionsProvider()))) {
            self.send(new ServerboundChatCommandPacket(command));
            return true;
        }
        return false;
    }
}
