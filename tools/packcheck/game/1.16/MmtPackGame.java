package mmt.packprobe;

import com.google.gson.JsonArray;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * The game calls pack-check's probe makes that changed between Minecraft versions, for Minecraft 1.16 and 1.16.1: as
 * 1.16.2's, except that the dimensions are not yet data the world saves through the registries (1.16.2 added
 * RegistryWriteOps and the server's registryAccess), so there is no dimension to write and check here.
 */
final class MmtPackGame {

    private MmtPackGame() {}

    static Iterable<Item> items() {
        return Registry.ITEM;
    }

    static String itemId(Item item) {
        return Registry.ITEM.getKey(item).toString();
    }

    static String itemNamespace(Item item) {
        return Registry.ITEM.getKey(item).getNamespace();
    }

    static String blockId(Block block) {
        return String.valueOf(Registry.BLOCK.getKey(block));
    }

    /** The server finished starting. */
    static boolean ready(MinecraftServer server) {
        return server.getTickCount() > 0;
    }

    static BlockPos spawn(ServerLevel level) {
        return level.getSharedSpawnPos();
    }

    /** The highest block a level can hold, as the level says: 255 up to 1.17, 319 on 1.18 and 1.18.1. */
    static int top(ServerLevel level) {
        return level.getMaxBuildHeight() - 1;
    }

    static ServerLevel overworld(MinecraftServer server) {
        return server.overworld();
    }

    static long chunkKey(int x, int z) {
        return net.minecraft.world.level.ChunkPos.asLong(x, z);
    }

    /**
     * Whether a player can find the item in a creative tab. An item in none is a block a mod places itself (a portal, a
     * spectral block) or a debug block (Applied Energistics 2's chunk loader, whose reload loads chunks while chunks load).
     */
    static boolean listed(Item item) {
        return item.getItemCategory() != null;
    }

    /**
     * Keeps a chunk loaded through the chunk system's own ticket, which loads it and its neighbours off the server
     * thread. Level.setChunkForced loads it at once on the server thread first, before its neighbours exist, and a mod
     * that reads a neighbour while the chunk finishes (Immersive Engineering's fluid hook) then loads chunk after chunk
     * there: All the Mods 10's watchdog stopped that after 300 s.
     */
    static void forceChunk(ServerLevel level, int x, int z) {
        level.getChunkSource().updateChunkForced(new net.minecraft.world.level.ChunkPos(x, z), true);
    }

    static void moveTo(ServerPlayer player, double x, double y, double z, float yaw, float pitch) {
        player.moveTo(x, y, z, yaw, pitch);
    }

    /** No dimension is saved as registry data before 1.16.2: nothing to write. */
    static JsonArray dimensions(MinecraftServer server) {
        return new JsonArray();
    }
}
