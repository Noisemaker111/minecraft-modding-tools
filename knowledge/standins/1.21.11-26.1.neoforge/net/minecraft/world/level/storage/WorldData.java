package mmt.standin.net.minecraft.world.level.storage;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * 1.21.11's WorldData on 26.1, through NeoForge's running server.
 *
 * Read from both releases: 1.21.11's WorldData (PrimaryLevelData) held the world's GameRules and its WorldOptions
 * (getGameRules(), worldGenOptions()), and the server read both from it. 26.1 moved both into saved data the server owns:
 * MinecraftServer.getGameRules() and MinecraftServer.getWorldGenSettings().options() (WorldGenSettings, a SavedData), and
 * WorldData keeps neither nor any way back to its server. A world's data belongs to the one server running it
 * (MinecraftServer.getWorldData()), which NeoForge names (ServerLifecycleHooks.getCurrentServer()). Data no running server
 * owns gets a default set of rules for its features, as 1.21.11 made for a new world.
 *
 * Agrees with NeoForge's 1.21.11 -> 26.1 primer (https://github.com/neoforged/.github/blob/main/primers/26.1/index.md, CC BY 4.0): "WorldData ... getGameRules replaced by GameRuleMap" and "worldGenOptions replaced by WorldGenSettings saved data".
 */
public final class WorldData {

    private WorldData() {}

    private static MinecraftServer owner(net.minecraft.world.level.storage.WorldData self) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && server.getWorldData() == self) {
            return server;
        }
        return null;
    }

    public static GameRules getGameRules(net.minecraft.world.level.storage.WorldData self) {
        MinecraftServer server = owner(self);
        if (server != null) {
            return server.getGameRules();
        }
        return new GameRules(self.enabledFeatures());
    }

    public static WorldOptions worldGenOptions(net.minecraft.world.level.storage.WorldData self) {
        MinecraftServer server = owner(self);
        if (server == null) {
            throw new IllegalStateException("26.1 keeps a world's generation options with its running server, and no running server owns this world's data");
        }
        return server.getWorldGenSettings().options();
    }
}
