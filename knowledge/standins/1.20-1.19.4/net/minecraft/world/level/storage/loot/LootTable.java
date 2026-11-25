package mmt.standin.net.minecraft.world.level.storage.loot;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.function.Consumer;
import net.minecraft.world.Container;

/**
 * 1.20's LootTable.getRandomItems(params[, output]) and fill(container, params, seed) on 1.19.4.
 *
 * Read from both releases: 1.20's make a context of the parameters for the roll (new LootContext.Builder(params), with
 * withOptionalRandomSeed(seed) for fill) and call what 1.19.4 calls with its LootContext.
 */
public final class LootTable {

    private LootTable() {
    }

    public static ObjectArrayList getRandomItems(net.minecraft.world.level.storage.loot.LootTable self, LootParams params) {
        return self.getRandomItems(params.roll());
    }

    public static void getRandomItems(net.minecraft.world.level.storage.loot.LootTable self, LootParams params, Consumer output) {
        self.getRandomItems(params.roll(), output);
    }

    public static void fill(net.minecraft.world.level.storage.loot.LootTable self, Container container, LootParams params, long seed) {
        self.fill(container, params.roll(seed));
    }
}
