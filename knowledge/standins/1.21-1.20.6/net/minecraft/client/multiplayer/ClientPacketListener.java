package mmt.standin.net.minecraft.client.multiplayer;

/**
 * 1.21's ClientPacketListener.searchTrees() on 1.20.6, for a port back: the session's search trees, which 1.20.6 keeps
 * in Minecraft's SearchRegistry for the whole game (see the stand-in SessionSearchTrees, a view of them).
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`searchTrees` - Gets the current search trees for creative tabs and recipe collections".
 */
public final class ClientPacketListener {

    private ClientPacketListener() {}

    public static SessionSearchTrees searchTrees(net.minecraft.client.multiplayer.ClientPacketListener self) {
        return new SessionSearchTrees(net.minecraft.client.Minecraft.getInstance());
    }
}
