package mmt.standin.net.minecraft.world.item.crafting;

import java.util.ArrayList;
import java.util.List;

/**
 * 1.21's CraftingInput on 1.20.6, for a port back: the class itself, which 1.20.6 does not have yet, as 1.21 writes it.
 *
 * Read from both releases: 1.21's CraftingInput is the grid's stacks cut to the rectangle that holds items (ofPositioned,
 * which also says where that rectangle started), with its width, height, the count of stacks that are not empty and
 * their StackedContents; CraftingContainer.asCraftInput() makes one from the container's width, height and items. 1.20.6
 * hands crafting recipes the CraftingContainer itself (getWidth(), getHeight(), getItems(), StackedContents exists in
 * both), so from(CraftingContainer) makes the same input from it, as 1.21's container does. What 1.21's game takes a
 * CraftingInput for, 1.20.6's takes a CraftingContainer for (RecipeManager.getRecipeFor, CraftingRecipe.matches and
 * assemble, which only read it), so the input is one too: a container that cannot be changed, as 1.21's input cannot.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`CraftingInput` is an implementation of `RecipeInput` which takes in width and height of the grid along with a list of `ItemStack`s. One can be created using `CraftingInput#of`."
 */
public class CraftingInput implements net.minecraft.world.inventory.CraftingContainer {

    public static final CraftingInput EMPTY = new CraftingInput(0, 0, List.of());

    private final int width;
    private final int height;
    private final List items;
    private final net.minecraft.world.entity.player.StackedContents stackedContents = new net.minecraft.world.entity.player.StackedContents();
    private final int ingredientCount;

    CraftingInput(int width, int height, List items) {
        this.width = width;
        this.height = height;
        this.items = items;
        int count = 0;
        for (Object o : items) {
            net.minecraft.world.item.ItemStack stack = (net.minecraft.world.item.ItemStack) o;
            if (!stack.isEmpty()) {
                count++;
                stackedContents.accountStack(stack, 1);
            }
        }
        this.ingredientCount = count;
    }

    public static CraftingInput from(net.minecraft.world.inventory.CraftingContainer container) {
        if (container instanceof CraftingInput input) {
            return input;
        }
        return ofPositioned(container.getWidth(), container.getHeight(), container.getItems()).input();
    }

    public static CraftingInput of(int width, int height, List items) {
        return ofPositioned(width, height, items).input();
    }

    public static CraftingInput$Positioned ofPositioned(int width, int height, List items) {
        if (width == 0 || height == 0) {
            return CraftingInput$Positioned.EMPTY;
        }
        int left = width - 1;
        int right = 0;
        int top = height - 1;
        int bottom = 0;
        for (int y = 0; y < height; y++) {
            boolean emptyRow = true;
            for (int x = 0; x < width; x++) {
                net.minecraft.world.item.ItemStack stack = (net.minecraft.world.item.ItemStack) items.get(x + y * width);
                if (!stack.isEmpty()) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    emptyRow = false;
                }
            }
            if (!emptyRow) {
                top = Math.min(top, y);
                bottom = Math.max(bottom, y);
            }
        }
        int w = right - left + 1;
        int h = bottom - top + 1;
        if (w <= 0 || h <= 0) {
            return CraftingInput$Positioned.EMPTY;
        }
        if (w == width && h == height) {
            return new CraftingInput$Positioned(new CraftingInput(width, height, items), left, top);
        }
        List cut = new ArrayList(w * h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                cut.add(items.get(x + left + (y + top) * width));
            }
        }
        return new CraftingInput$Positioned(new CraftingInput(w, h, cut), left, top);
    }

    public net.minecraft.world.item.ItemStack getItem(int index) {
        return (net.minecraft.world.item.ItemStack) items.get(index);
    }

    public net.minecraft.world.item.ItemStack getItem(int x, int y) {
        return (net.minecraft.world.item.ItemStack) items.get(x + y * width);
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return ingredientCount == 0;
    }

    public net.minecraft.world.entity.player.StackedContents stackedContents() {
        return stackedContents;
    }

    public List items() {
        return items;
    }

    public int ingredientCount() {
        return ingredientCount;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int getContainerSize() {
        return items.size();
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

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public List getItems() {
        return items;
    }

    public void fillStackedContents(net.minecraft.world.entity.player.StackedContents contents) {
        for (Object o : items) {
            contents.accountSimpleStack((net.minecraft.world.item.ItemStack) o);
        }
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        return o instanceof CraftingInput other && width == other.width && height == other.height && ingredientCount == other.ingredientCount
            && net.minecraft.world.item.ItemStack.listMatches(items, other.items);
    }

    public int hashCode() {
        int h = net.minecraft.world.item.ItemStack.hashStackList(items);
        h = 31 * h + width;
        return 31 * h + height;
    }
}
