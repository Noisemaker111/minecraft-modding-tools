package mmt.standin.net.minecraft.server;

/**
 * 1.15.2's MinecraftServer.getLevelIdName() on 1.16.
 *
 * Read from both releases: 1.15.2's getLevelIdName() returned the name of the world's folder under the saves directory
 * (the level id the server was started with). 1.16 opens that folder as a LevelStorageSource.LevelStorageAccess, kept
 * in the server's storageSource, whose getLevelId() returns the same folder name. This returns it.
 *
 * Fabric's 1.16 post (https://fabricmc.net/2020/06/23/116.html) does not mention it; this is from the game's code alone.
 */
public final class MinecraftServer {

    private MinecraftServer() {}

    public static String getLevelIdName(net.minecraft.server.MinecraftServer self) {
        return self.storageSource.getLevelId();
    }
}
