package mmt.standin.net.minecraft.network.protocol.game;

/**
 * 1.20.1's ClientboundLoginPacket.seed() on 1.20.2.
 *
 * Read from both releases: 1.20.1's login packet was a record with the world's hashed seed among its own parts (seed()).
 * 1.20.2 moved the parts it shares with the respawn packet into CommonPlayerSpawnInfo (commonPlayerSpawnInfo()), the
 * hashed seed among them (seed()), written in the same place; ClientPacketListener.handleLogin reads it from there.
 *
 * NeoForge's primers begin at 1.20.2 -> 1.20.3, and Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html) does
 * not mention the login packet; this is from the game's code alone.
 */
public final class ClientboundLoginPacket {

    private ClientboundLoginPacket() {}

    public static long seed(net.minecraft.network.protocol.game.ClientboundLoginPacket self) {
        return self.commonPlayerSpawnInfo().seed();
    }
}
