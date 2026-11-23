package mmt.standin.net.minecraft.client.multiplayer;

import java.net.UnknownHostException;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ServerData;

/**
 * 1.20.4's ServerStatusPinger on 1.20.5.
 *
 * Read from both releases: 1.20.4's pingServer(data, onPersistentDataChange) pinged the server and filled in data
 * (status, players, version, ping), its caller having set data.pinged first. 1.20.5's pingServer takes one more
 * Runnable, run when the pong arrives, with which its server list sets the data's new state: SUCCESSFUL when the
 * server speaks this release's protocol, else INCOMPATIBLE (ServerSelectionList$OnlineServerEntry). The old call runs
 * that same step, so data whose ping the old pinged flag started ends in the state the game's own list gives it.
 *
 * Agrees with NeoForge's 1.20.4 -> 1.20.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.20.5/index.md, CC BY 4.0): "ServerStatusPinger#pingServer now takes in a runnable that runs on response from the pong packet".
 */
public final class ServerStatusPinger {

    private ServerStatusPinger() {}

    public static void pingServer(net.minecraft.client.multiplayer.ServerStatusPinger self, ServerData data, Runnable onPersistentDataChange) throws UnknownHostException {
        self.pingServer(data, onPersistentDataChange, () -> data.setState(data.protocol == SharedConstants.getCurrentVersion().getProtocolVersion() ? ServerData.State.SUCCESSFUL : ServerData.State.INCOMPATIBLE));
    }
}
