package mmt.standin.net.minecraft.network.protocol.game;

import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.ChunkPos;

/**
 * 1.16.1's ClientboundChunkBlocksUpdatePacket.chunkPos, read on 1.16.2's ClientboundSectionBlocksUpdatePacket.
 *
 * Read from both releases: 1.16.1 sent the blocks changed in one chunk with the chunk's position (chunkPos). 1.16.2
 * sends them per 16-block section with the section's position (sectionPos, a SectionPos), whose x and z are the chunk's.
 * This gives the chunk of the section.
 *
 * Fabric's 1.16.2 post (https://fabricmc.net/2020/08/11/1162.html) does not mention it; this is from the game's code alone.
 */
public final class ClientboundChunkBlocksUpdatePacket {

    private ClientboundChunkBlocksUpdatePacket() {}

    public static ChunkPos chunkPos(ClientboundSectionBlocksUpdatePacket self) {
        SectionPos section = self.sectionPos;
        return new ChunkPos(section.x(), section.z());
    }
}
