package mmt.standin.net.minecraft.world.inventory;

import mmt.standin.net.minecraft.world.item.crafting.CraftingInput;

/**
 * 1.21's CraftingContainer.asCraftInput() and asPositionedCraftInput() on 1.20.6, for a port back: the stand-in
 * CraftingInput made from the container, as 1.21's defaults make it (CraftingInput.ofPositioned(getWidth(),
 * getHeight(), getItems())), from the three methods 1.20.6's CraftingContainer also has.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`CraftingInput` is an implementation of `RecipeInput` which takes in width and height of the grid along with a list of `ItemStack`s."
 */
public final class CraftingContainer {

    private CraftingContainer() {}

    public static CraftingInput asCraftInput(net.minecraft.world.inventory.CraftingContainer self) {
        return CraftingInput.from(self);
    }

    public static mmt.standin.net.minecraft.world.item.crafting.CraftingInput$Positioned asPositionedCraftInput(net.minecraft.world.inventory.CraftingContainer self) {
        return CraftingInput.ofPositioned(self.getWidth(), self.getHeight(), self.getItems());
    }
}
