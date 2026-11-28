package mmt.standin.net.minecraft.world.item.crafting;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;

/**
 * 1.21.1's Ingredient.getItems(), of(ItemStack...) and of(TagKey) on 1.21.2.
 *
 * Read from both releases: 1.21.1's Ingredient was a list of values (an item, or a tag of items), and getItems() gave one
 * stack of each item they matched (new ItemStack(item), count 1, no components), distinct; of(stacks...) made it of the
 * stacks' items and of(tag) of the tag. 1.21.2's Ingredient is a HolderSet of items: items() lists them as holders,
 * of(Stream<ItemLike>) and of(HolderSet) make one, and a tag is the registry's named set for it. So getItems() is a stack
 * of each listed item, of(stacks...) the ingredient of their items, and of(tag) the ingredient of the tag's set, taken as
 * 1.21.1's getOrCreateTag took it (the bound set, or the kept one tags bind on load).
 *
 * Known difference: 1.21.1 matched only a stack's item (Ingredient.test asked stack.is(item)) but showed the stacks it was
 * made of, components and all, through getItems() (a tipped-arrow recipe showed the lingering potion it needs). 1.21.2's
 * vanilla Ingredient keeps items only, so these stacks have no components: the matching is the same, the display loses
 * them. NeoForge 21.2 can carry a display beside the matching (CustomDisplayIngredient), but this file is read for every
 * loader and a NeoForge-only copy of the class cannot sit beside it (both folders compile together), so it is not used.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "getItems -> items", "of(ItemStack...), of(Stream<ItemStack>) is removed", and "of(TagKey) -> of(HolderSet), need to resolve tag key".
 */
public final class Ingredient {

    private Ingredient() {}

    public static ItemStack[] getItems(net.minecraft.world.item.crafting.Ingredient self) {
        List holders = self.items();
        ItemStack[] stacks = new ItemStack[holders.size()];
        for (int i = 0; i < stacks.length; i++) {
            stacks[i] = new ItemStack((Holder) holders.get(i));
        }
        return stacks;
    }

    public static net.minecraft.world.item.crafting.Ingredient of(ItemStack[] stacks) {
        return net.minecraft.world.item.crafting.Ingredient.of(Arrays.stream(stacks).filter(stack -> !stack.isEmpty()).map(ItemStack::getItem));
    }

    public static net.minecraft.world.item.crafting.Ingredient of(TagKey tag) {
        return net.minecraft.world.item.crafting.Ingredient.of(tag(BuiltInRegistries.ITEM, tag));
    }

    static HolderSet.Named tag(Registry registry, TagKey key) {
        try {
            Optional bound = registry.get(key);
            if (bound.isPresent()) {
                return (HolderSet.Named) bound.get();
            }
        } catch (IllegalStateException unbound) {
            // tags not bound yet: the kept set below is bound when they are
        }
        if (registry instanceof MappedRegistry mapped) {
            return mapped.getOrCreateTagForRegistration(key);
        }
        return registry.getOrThrow(key);
    }
}
