package mmt.standin.net.minecraft.client.multiplayer;

/**
 * 1.20.4's ServerData on 1.20.5.
 *
 * Read from both releases: 1.20.4's server list set the public field pinged to true when it started pinging an entry
 * and read it to ping each entry once; nothing else set it. 1.20.5 replaced it with state() and setState(state): its
 * list pings an entry whose state is INITIAL and sets PINGING first (ServerSelectionList$OnlineServerEntry), and the
 * ping's end sets SUCCESSFUL, INCOMPATIBLE or UNREACHABLE. So pinged is any state but INITIAL; setting it starts
 * PINGING on an entry not yet pinged, and clearing it puts the entry back to INITIAL to be pinged again.
 *
 * Agrees with NeoForge's 1.20.4 -> 1.20.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.20.5/index.md, CC BY 4.0): "ServerData", "state - Gets the current state of the server data", "setState - Sets the current state of the server data".
 */
public final class ServerData {

    private ServerData() {}

    public static boolean pinged(net.minecraft.client.multiplayer.ServerData self) {
        return self.state() != net.minecraft.client.multiplayer.ServerData.State.INITIAL;
    }

    public static void pinged(net.minecraft.client.multiplayer.ServerData self, boolean pinged) {
        if (!pinged) {
            self.setState(net.minecraft.client.multiplayer.ServerData.State.INITIAL);
        } else if (self.state() == net.minecraft.client.multiplayer.ServerData.State.INITIAL) {
            self.setState(net.minecraft.client.multiplayer.ServerData.State.PINGING);
        }
    }
}
