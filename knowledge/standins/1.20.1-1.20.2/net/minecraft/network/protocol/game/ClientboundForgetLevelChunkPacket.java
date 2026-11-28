package mmt.standin.net.minecraft.network.protocol.game;

/**
 * 1.20.1's ClientboundForgetLevelChunkPacket getters on 1.20.2.
 *
 * Read from both releases: 1.20.1's packet held the chunk's x and z (getX(), getZ()). 1.20.2's is a record of the chunk's
 * ChunkPos (pos()), written and read as the same two coordinates; ClientPacketListener.handleForgetLevelChunk reads
 * pos() where 1.20.1 read getX() and getZ().
 *
 * NeoForge's primers begin at 1.20.2 -> 1.20.3, and Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html) does
 * not mention this packet; this is from the game's code alone.
 */
public final class ClientboundForgetLevelChunkPacket {

    private ClientboundForgetLevelChunkPacket() {}

    public static int getX(net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket self) {
        return self.pos().x;
    }

    public static int getZ(net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket self) {
        return self.pos().z;
    }
}
