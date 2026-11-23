package mmt.standin.net.minecraft.world.level;

/**
 * 1.21.8's Level.getSharedSpawnAngle() on 1.21.9.
 *
 * Read from both releases: 1.21.8's getSharedSpawnAngle() answered the world spawn's yaw as the level data kept it
 * (levelData.getSpawnAngle()). 1.21.9 keeps the spawn's dimension, position, yaw and pitch together in one
 * LevelData.RespawnData, which getRespawnData() answers from the same level data; its yaw() is the old angle (the border
 * adjustment 1.21.9 applies in getWorldBorderAdjustedRespawnData moves only the position).
 *
 * Agrees with NeoForge's 1.21.8 -> 1.21.9 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.9/index.md, CC BY 4.0):
 * "Level#getSharedSpawnPos, getSharedSpawnAngle -> getRespawnData, getWorldBorderAdjustedRespawnData, not one-to-one".
 */
public final class Level {

    private Level() {}

    public static float getSharedSpawnAngle(net.minecraft.world.level.Level self) {
        return self.getRespawnData().yaw();
    }
}
