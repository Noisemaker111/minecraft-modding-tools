package mmt.standin.net.minecraft.world.level.lighting;

/**
 * 1.20's LevelLightEngine.runLightUpdates() on 1.19.4.
 *
 * Read from both releases: 1.20 runs every queued light update of both layers at once (ThreadedLevelLightEngine.runUpdate
 * calls super.runLightUpdates()); 1.19.4 does the same with no limit, as its own ThreadedLevelLightEngine.runUpdate does:
 * runUpdates(Integer.MAX_VALUE, true, true). The number each gives back is not the same one: 1.20's counts the updates
 * run, 1.19.4's what is left of the limit.
 */
public final class LevelLightEngine {

    private LevelLightEngine() {
    }

    public static int runLightUpdates(net.minecraft.world.level.lighting.LevelLightEngine self) {
        return self.runUpdates(Integer.MAX_VALUE, true, true);
    }
}
