package mmt.standin.net.minecraft.world.item.crafting;

import java.util.Optional;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.ShapedRecipePattern;

/**
 * 1.20.2's ShapedRecipe on 1.20.3.
 *
 * Read from both releases: 1.20.2's ShapedRecipe(group, category, width, height, ingredients, result[, showNotification])
 * kept the grid as its width, height and ingredient list (showNotification true by default). 1.20.3 keeps the grid in a
 * ShapedRecipePattern(width, height, ingredients, data) and takes that in its place; data is the key and rows a recipe was
 * read from, empty for one built from its grid (an "unpacked recipe" to the pattern's codec), which a grid made in code
 * is. So each old constructor makes the new one from the pattern of the same grid.
 *
 * NeoForge's primers begin at 1.20.4 -> 1.20.5, and Fabric's 1.20.3 post (https://fabricmc.net/2023/11/30/1203.html)
 * mentions only recipe builders changing; this is from the game's code alone.
 */
public final class ShapedRecipe {

    private ShapedRecipe() {}

    public static net.minecraft.world.item.crafting.ShapedRecipe init(String group, CraftingBookCategory category, int width, int height, NonNullList ingredients, ItemStack result, boolean showNotification) {
        return new net.minecraft.world.item.crafting.ShapedRecipe(group, category, new ShapedRecipePattern(width, height, ingredients, Optional.empty()), result, showNotification);
    }

    public static net.minecraft.world.item.crafting.ShapedRecipe init(String group, CraftingBookCategory category, int width, int height, NonNullList ingredients, ItemStack result) {
        return init(group, category, width, height, ingredients, result, true);
    }
}
