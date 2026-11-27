package mmt.standin.net.minecraft.world.item.crafting;

/**
 * 1.21's CraftingInput.Positioned on 1.20.6, for a port back: a crafting input with where its rectangle started in the
 * grid (see the stand-in CraftingInput, which makes them).
 *
 * Read from 1.21: the record (input, left, top), EMPTY the empty input at 0, 0. 1.20.6 has no such type.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`CraftingInput` is an implementation of `RecipeInput` which takes in width and height of the grid along with a list of `ItemStack`s."
 */
public final class CraftingInput$Positioned {

    public static final CraftingInput$Positioned EMPTY = new CraftingInput$Positioned(CraftingInput.EMPTY, 0, 0);

    private final CraftingInput input;
    private final int left;
    private final int top;

    public CraftingInput$Positioned(CraftingInput input, int left, int top) {
        this.input = input;
        this.left = left;
        this.top = top;
    }

    public CraftingInput input() {
        return input;
    }

    public int left() {
        return left;
    }

    public int top() {
        return top;
    }
}
