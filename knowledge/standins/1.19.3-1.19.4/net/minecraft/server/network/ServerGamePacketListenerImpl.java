package mmt.standin.net.minecraft.server.network;

import net.minecraft.network.Connection;

/**
 * 1.19.3's ServerGamePacketListenerImpl on 1.19.4.
 *
 * Read from both releases: 1.19.3's getConnection() gave the player's connection, the final field connection set by the
 * constructor. 1.19.4 keeps the same field, now private, and dropped the getter; reading the field is the old getter
 * (the port opens it through the mod's access transformer or widener).
 */
public final class ServerGamePacketListenerImpl {

    private ServerGamePacketListenerImpl() {}

    public static Connection getConnection(net.minecraft.server.network.ServerGamePacketListenerImpl self) {
        return self.connection;
    }
}
