package mmt.standin.net.minecraft.world.item.crafting;

/**
 * 1.21's SmithingRecipeInput on 1.20.6, for a port back: the class itself, which 1.20.6 does not have yet.
 *
 * Read from both releases: 1.21's smithing recipes take the record (template, base, addition), slots 0, 1 and 2, empty
 * only when all three are; 1.20.6's take the smithing menu's Container with the same three in slots 0, 1 and 2, so
 * from(Container) reads them. Like 1.21's record it cannot be changed; it is a Container too, which is what 1.20.6's
 * recipe code takes where 1.21's takes the input.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`Recipe`s now take in a `net.minecraft.world.item.crafting.RecipeInput` instead of a `Container`. A `RecipeInput` is basically a minimal view into a list of available stacks."
 */
public final class SmithingRecipeInput implements net.minecraft.world.Container {

    private final net.minecraft.world.item.ItemStack template;
    private final net.minecraft.world.item.ItemStack base;
    private final net.minecraft.world.item.ItemStack addition;

    public SmithingRecipeInput(net.minecraft.world.item.ItemStack template, net.minecraft.world.item.ItemStack base, net.minecraft.world.item.ItemStack addition) {
        this.template = template;
        this.base = base;
        this.addition = addition;
    }

    public static SmithingRecipeInput from(net.minecraft.world.Container container) {
        if (container instanceof SmithingRecipeInput input) {
            return input;
        }
        return new SmithingRecipeInput(container.getItem(0), container.getItem(1), container.getItem(2));
    }

    public net.minecraft.world.item.ItemStack template() {
        return template;
    }

    public net.minecraft.world.item.ItemStack base() {
        return base;
    }

    public net.minecraft.world.item.ItemStack addition() {
        return addition;
    }

    public net.minecraft.world.item.ItemStack getItem(int index) {
        if (index == 0) {
            return template;
        }
        if (index == 1) {
            return base;
        }
        if (index == 2) {
            return addition;
        }
        throw new IllegalArgumentException("Recipe does not contain slot " + index);
    }

    public int size() {
        return 3;
    }

    public boolean isEmpty() {
        return template.isEmpty() && base.isEmpty() && addition.isEmpty();
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
