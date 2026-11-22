package mmt.standin.net.minecraft.server.packs.resources;

import net.minecraft.server.packs.resources.SimpleReloadInstance;

/**
 * 1.16.5's ReloadInstance.isApplying() on 1.17.
 *
 * Read from both releases: 1.16.5's SimpleReloadInstance (and the profiled one, its subclass) answered isApplying() with
 * whether every listener had finished preparing (allPreparations done), so the reload was applying or done. 1.17 keeps
 * the same allPreparations future in SimpleReloadInstance and dropped the method from the interface. This asks the
 * future; a reload instance of another kind answers whether it is done.
 *
 * Fabric's 1.17 post for developers (https://fabricmc.net/2021/05/27/117-for-developers.html) covers Java 16, Fabric API and
 * Yarn, not this; this is from the game's code alone.
 */
public final class ReloadInstance {

    private ReloadInstance() {}

    public static boolean isApplying(net.minecraft.server.packs.resources.ReloadInstance self) {
        if (self instanceof SimpleReloadInstance simple) {
            return simple.allPreparations.isDone();
        }
        return self.isDone();
    }
}
