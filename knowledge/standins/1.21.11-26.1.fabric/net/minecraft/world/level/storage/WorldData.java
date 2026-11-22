package mmt.standin.net.minecraft.world.level.storage;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;

/**
 * 1.21.11's WorldData on 26.1, through the game Fabric Loader runs.
 *
 * Read from both releases: 1.21.11's WorldData (PrimaryLevelData) held the world's GameRules and its WorldOptions
 * (getGameRules(), worldGenOptions()), and the server read both from it. 26.1 moved both into saved data the server owns:
 * MinecraftServer.getGameRules() and MinecraftServer.getWorldGenSettings().options() (WorldGenSettings, a SavedData), and
 * WorldData keeps neither nor any way back to its server. A world's data belongs to the one server running it
 * (MinecraftServer.getWorldData()): the game itself on a dedicated server, the client's integrated server in single player
 * (Minecraft.getSingleplayerServer()); Fabric Loader names the game (FabricLoader.getGameInstance(), the server or the
 * client). Data no running server owns gets a default set of rules for its features, as 1.21.11 made for a new world.
 *
 * Agrees with NeoForge's 1.21.11 -> 26.1 primer (https://github.com/neoforged/.github/blob/main/primers/26.1/index.md, CC BY 4.0): "WorldData ... getGameRules replaced by GameRuleMap" and "worldGenOptions replaced by WorldGenSettings saved data". Fabric's 26.1 post (https://fabricmc.net/2026/03/14/261.html) does not mention WorldData.
 */
public final class WorldData {

    private WorldData() {}

    private static MinecraftServer owner(net.minecraft.world.level.storage.WorldData self) {
        Object game = FabricLoader.getInstance().getGameInstance();
        MinecraftServer server = game instanceof MinecraftServer running ? running : Client.server(game);
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

    /** The client's classes, touched only when the game is the client (a dedicated server has none). */
    static final class Client {
        static MinecraftServer server(Object game) {
            return game instanceof Minecraft client ? client.getSingleplayerServer() : null;
        }
    }
}
