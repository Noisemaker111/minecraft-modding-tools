package mmt.standin.net.minecraft.world.item.crafting;

import com.google.gson.JsonElement;

/**
 * 1.20.1's Ingredient.fromJson(json, canBeEmpty) on 1.19.4.
 *
 * Read from both releases: the two bodies are the same, but 1.20.1 throws "Item array cannot be empty" for an empty array
 * only when canBeEmpty is false, and otherwise makes the ingredient of no values (Ingredient.EMPTY); 1.19.4's
 * fromJson(json) always throws there. So an empty array with canBeEmpty is Ingredient.EMPTY, and the rest is 1.19.4's.
 */
public final class Ingredient {

    private Ingredient() {
    }

    public static net.minecraft.world.item.crafting.Ingredient fromJson(JsonElement json, boolean canBeEmpty) {
        if (canBeEmpty && json != null && json.isJsonArray() && json.getAsJsonArray().size() == 0) {
            return net.minecraft.world.item.crafting.Ingredient.EMPTY;
        }
        return net.minecraft.world.item.crafting.Ingredient.fromJson(json);
    }
}
