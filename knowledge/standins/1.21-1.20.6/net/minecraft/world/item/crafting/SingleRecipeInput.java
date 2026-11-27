package mmt.standin.net.minecraft.world.item.crafting;

/**
 * 1.21's SingleRecipeInput on 1.20.6, for a port back: the class itself, which 1.20.6 does not have yet.
 *
 * Read from both releases: 1.21's furnace, stonecutter and campfire recipes take the record (item), one stack at index 0;
 * 1.20.6's take the Container whose slot 0 is that stack (the furnace's input slot, the stonecutter's), so from(Container)
 * reads slot 0. Like 1.21's record it cannot be changed; it is a Container too, which is what 1.20.6's
 * recipe code takes where 1.21's takes the input (RecipeManager.getRecipeFor, Recipe.matches and assemble).
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`SingleRecipeInput` is an implementation of `RecipeInput` that only has one item. One can be created using the constructor."
 */
public final class SingleRecipeInput implements net.minecraft.world.Container {

    private final net.minecraft.world.item.ItemStack item;

    public SingleRecipeInput(net.minecraft.world.item.ItemStack item) {
        this.item = item;
    }

    public static SingleRecipeInput from(net.minecraft.world.Container container) {
        if (container instanceof SingleRecipeInput input) {
            return input;
        }
        return new SingleRecipeInput(container.getItem(0));
    }

    public net.minecraft.world.item.ItemStack item() {
        return item;
    }

    public net.minecraft.world.item.ItemStack getItem(int index) {
        if (index != 0) {
            throw new IllegalArgumentException("No item for index " + index);
        }
        return item;
    }

    public int size() {
        return 1;
    }

    public boolean isEmpty() {
        return item.isEmpty();
    }

    public int getContainerSize() {
        return size();
    }

    public net.minecraft.world.item.ItemStack removeItem(int index, int count) {
        return net.minecraft.world.item.ItemStack.EMPTY;
    }

    public net.minecraft.world.item.ItemStack removeItemNoUpdate(int index) {
        return net.minecraft.world.item.ItemStack.EMPTY;
    }

    public void setItem(int index, net.minecraft.world.item.ItemStack stack) {
    }

    public void setChanged() {
    }

    public boolean stillValid(net.minecraft.world.entity.player.Player player) {
        return true;
    }

    public void clearContent() {
    }
}
