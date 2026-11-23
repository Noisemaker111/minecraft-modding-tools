package mmt.standin.net.minecraft.client.gui.screens;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * 1.20.4's ConnectScreen on 1.20.5.
 *
 * Read from both releases: 1.20.5's startConnecting takes one more argument, the TransferState of a server transfer
 * (cookies the old server handed over), and every connection the game itself starts from a screen (the server list,
 * direct connect, a quick-play server) passes null: JoinMultiplayerScreen.join calls startConnecting(this, minecraft,
 * address, data, false, null). An old call is one of those, with no transfer behind it.
 *
 * Agrees with NeoForge's 1.20.4 -> 1.20.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.20.5/index.md, CC BY 4.0): "ConnectScreen#startConnecting takes in the current TransferState".
 */
public final class ConnectScreen {

    private ConnectScreen() {}

    public static void startConnecting(Screen parent, net.minecraft.client.Minecraft minecraft, ServerAddress address, ServerData data, boolean quickPlay) {
        net.minecraft.client.gui.screens.ConnectScreen.startConnecting(parent, minecraft, address, data, quickPlay, null);
    }
}
