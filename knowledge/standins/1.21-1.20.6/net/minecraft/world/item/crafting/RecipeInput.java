package mmt.standin.net.minecraft.world.item.crafting;

/**
 * 1.21's RecipeInput on 1.20.6, for a port back: its members asked of the Container 1.20.6's recipes take instead.
 *
 * Read from both releases: 1.21's recipes take a RecipeInput (getItem(int), size(), isEmpty() true when every stack is
 * empty) where 1.20.6's take the Container the stacks are in, which the port moves RecipeInput to: getItem(int) is the
 * same, size() is getContainerSize(), and isEmpty() asks each stack as 1.21's default does (a Container's own isEmpty()
 * is its own, an inventory's).
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`Recipe`s now take in a `net.minecraft.world.item.crafting.RecipeInput` instead of a `Container`. A `RecipeInput` is basically a minimal view into a list of available stacks."
 */
public final class RecipeInput {

    private RecipeInput() {}

    public static net.minecraft.world.item.ItemStack getItem(net.minecraft.world.Container self, int index) {
        return self.getItem(index);
    }

    public static int size(net.minecraft.world.Container self) {
        return self.getContainerSize();
    }

    public static boolean isEmpty(net.minecraft.world.Container self) {
        for (int i = 0; i < self.getContainerSize(); i++) {
            if (!self.getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
