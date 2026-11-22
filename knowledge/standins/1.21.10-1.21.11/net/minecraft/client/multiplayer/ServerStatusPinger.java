package mmt.standin.net.minecraft.client.multiplayer;

import java.net.UnknownHostException;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.network.EventLoopGroupHolder;

/**
 * 1.21.10's ServerStatusPinger on 1.21.11.
 *
 * Read from both releases: 1.21.10's pingServer(data, onChange, onSuccess) connected to the server with
 * Connection.connectToServer(address, false, null), never the native transport; 1.21.11's pingServer takes the
 * EventLoopGroupHolder to connect with and is otherwise the same steps. Not native is EventLoopGroupHolder.remote(false).
 *
 * Agrees with NeoForge's 1.21.10 -> 1.21.11 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.11/index.md, CC BY 4.0): "ServerStatusPinger#pingServer now takes in an EventLoopGroupHolder".
 */
public final class ServerStatusPinger {

    private ServerStatusPinger() {}

    public static void pingServer(net.minecraft.client.multiplayer.ServerStatusPinger self, ServerData data, Runnable onChange, Runnable onSuccess) throws UnknownHostException {
        self.pingServer(data, onChange, onSuccess, EventLoopGroupHolder.remote(false));
    }
}
