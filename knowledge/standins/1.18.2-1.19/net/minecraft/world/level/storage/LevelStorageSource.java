package mmt.standin.net.minecraft.world.level.storage;

import java.util.List;

/**
 * 1.18.2's LevelStorageSource on 1.19.
 *
 * Read from both releases: 1.18.2's getLevelList() read the summary of every world folder under the saves directory
 * (each folder's level.dat, through levelSummaryReader) and gave them as a list, throwing LevelStorageException when the
 * directory could not be read. 1.19 split it in two, as its world selection list calls them: findLevelCandidates()
 * lists the world folders (throwing the same exception) and loadLevelSummaries(candidates) reads their summaries on a
 * worker, giving a future of the list. The old call waits for that future.
 */
public final class LevelStorageSource {

    private LevelStorageSource() {}

    public static List getLevelList(net.minecraft.world.level.storage.LevelStorageSource self) {
        try {
            return (List) self.loadLevelSummaries(self.findLevelCandidates()).join();
        } catch (Exception failed) {
            throw LevelStorageSource.<RuntimeException>rethrow(failed);
        }
    }

    /** findLevelCandidates' LevelStorageException as itself, as getLevelList threw it (declared in the game, left out of the port's stubs). */
    private static <E extends Throwable> E rethrow(Throwable failed) throws E {
        throw (E) failed;
    }
}
