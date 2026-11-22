package mmt.standin.net.minecraft.world.level;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;

/**
 * 1.21.1's Level on 1.21.2.
 *
 * Read from both releases: 1.21.1's Level.getGameRules() returned levelData.getGameRules(), on the server the world's
 * rules and on the client ClientLevel.ClientLevelData's own default set (new GameRules(), never synced). 1.21.2 dropped
 * it from Level and from the client's level data: only ServerLevel.getGameRules() remains, and GameRules is now made for
 * a set of enabled features (new GameRules(FeatureFlagSet)).
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "getGameRules -> ServerLevel#getGameRules" and "LevelData#getGameRules -> ServerLevelData#getGameRules". The client level's default set is from the game's code; the primer does not say.
 */
public final class Level {

    private static final Map<net.minecraft.world.level.Level, GameRules> CLIENT = Collections.synchronizedMap(new WeakHashMap<>());

    private Level() {}

    /** The server level's rules; a client level gets a default set of its own, kept for the level as 1.21.1 kept one. */
    public static GameRules getGameRules(net.minecraft.world.level.Level self) {
        if (self instanceof ServerLevel server) {
            return server.getGameRules();
        }
        return CLIENT.computeIfAbsent(self, level -> new GameRules(level.enabledFeatures()));
    }
}
