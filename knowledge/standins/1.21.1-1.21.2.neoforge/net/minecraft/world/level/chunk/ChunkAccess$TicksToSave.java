package mmt.standin.net.minecraft.world.level.chunk;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.ticks.SerializableTickContainer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * 1.21.1's new ChunkAccess.TicksToSave(blocks, fluids) on 1.21.2, where the route takes the class to ChunkAccess.PackedTicks.
 *
 * Read from both releases: 1.21.1's getTicksForSerialization() returned a TicksToSave holding the chunk's two tick
 * containers, and ChunkSerializer.write packed them at once with the level's game time (blocks().save(level.getGameTime(),
 * ...)). 1.21.2's getTicksForSerialization(long gameTime) packs them itself: LevelChunk returns new PackedTicks(
 * blockTicks.pack(gameTime), fluidTicks.pack(gameTime)), and SerializableChunkData.copyOf passes level.getGameTime(), which
 * every level of a server reads from the overworld's data. So the old holder is the two containers packed with the running
 * server's game time (NeoForge's ServerLifecycleHooks.getCurrentServer()), or 0 with no server, as a chunk read on no
 * server has no time to save against.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "getTicksForSerialization now takes in a long of the game time" and "$TicksToSave -> $PackedTicks".
 */
public final class ChunkAccess$TicksToSave {

    private ChunkAccess$TicksToSave() {}

    public static ChunkAccess.PackedTicks init(SerializableTickContainer blocks, SerializableTickContainer fluids) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        long gameTime = server == null ? 0L : server.overworld().getGameTime();
        return new ChunkAccess.PackedTicks(blocks.pack(gameTime), fluids.pack(gameTime));
    }
}
