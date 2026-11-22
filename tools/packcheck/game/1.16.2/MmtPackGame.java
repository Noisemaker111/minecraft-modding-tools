package mmt.packprobe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.dimension.LevelStem;

/**
 * The game calls pack-check's probe makes that changed between Minecraft versions, for Minecraft 1.16.2 to 1.18.1: Java 8
 * source (1.16 servers run on Java 8), registries as static fields of Registry, and the dimensions in the world's
 * generation settings, written with RegistryWriteOps (1.16.2 to 1.18.1; 1.18.2 replaced it with RegistryOps). Reading a
 * dimension back needs the server's data pack resources here, so each one is only written the way level.dat stores it;
 * the second start still opens the world for real.
 * pack-check compiles the probe with the variant in tools/packcheck/game/ whose folder names the newest version at or
 * below the pack's, so one probe source serves every version; each variant keeps the same methods.
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

    /** Each dimension written the way level.dat stores it; a dimension that cannot be written is named. */
    static JsonArray dimensions(MinecraftServer server) {
        JsonArray out = new JsonArray();
        net.minecraft.resources.RegistryWriteOps<net.minecraft.nbt.Tag> ops = net.minecraft.resources.RegistryWriteOps.create(
            net.minecraft.nbt.NbtOps.INSTANCE,
            server.registryAccess()
        );
        for (Map.Entry<ResourceKey<LevelStem>, LevelStem> entry : server.getWorldData().worldGenSettings().dimensions().entrySet()) {
            JsonObject row = new JsonObject();
            row.addProperty("dimension", entry.getKey().location().toString());
            try {
                com.mojang.serialization.DataResult<net.minecraft.nbt.Tag> written = LevelStem.CODEC.encodeStart(ops, entry.getValue());
                if (written.error().isPresent()) {
                    String message = written.error().get().message();
                    row.addProperty("write", message.length() > 600 ? message.substring(0, 600) + "..." : message);
                } else row.addProperty("savedBytes", written.result().get().toString().length());
            } catch (Throwable failure) {
                row.addProperty("error", MmtPackProbe.stack(failure));
            }
            out.add(row);
        }
        return out;
    }
}
