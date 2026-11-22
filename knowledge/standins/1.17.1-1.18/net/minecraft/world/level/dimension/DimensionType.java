package mmt.standin.net.minecraft.world.level.dimension;

import java.io.File;
import net.minecraft.resources.ResourceKey;

/**
 * 1.17.1's DimensionType.getStorageFolder(ResourceKey, File) on 1.18.
 *
 * Read from both releases: both pick the same folder for a dimension under the world's folder (the overworld's own,
 * DIM1 for the end, DIM-1 for the nether, dimensions/NAMESPACE/PATH otherwise); 1.17.1 took and returned a File, 1.18
 * takes and returns a Path. This calls 1.18's with the File as a Path.
 *
 * Fabric's 1.18 post (https://fabricmc.net/2021/11/30/118.html) does not mention it; this is from the game's code alone.
 */
public final class DimensionType {

    private DimensionType() {}

    public static File getStorageFolder(ResourceKey dimension, File root) {
        return net.minecraft.world.level.dimension.DimensionType.getStorageFolder(dimension, root.toPath()).toFile();
    }
}
