package mmt.standin.net.minecraft.world.item;

import java.util.stream.Stream;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.world.level.ItemLike;

/**
 * 1.21.11's ItemStack(item, count, components) constructor and getTags() on 26.1.
 *
 * Read from both releases: 1.21.11's private ItemStack(ItemLike, int, PatchedDataComponentMap) kept item.asItem(), the
 * count and the given component map as they were; getTags() answered the item's registry holder's tags
 * (getItem().builtInRegistryHolder().tags()). 26.1 keeps the stack's item as a Holder<Item>: its private
 * ItemStack(Holder<Item>, int, PatchedDataComponentMap) keeps the three as they are, and its own public ItemStack(ItemLike,
 * int) passes item.asItem().builtInRegistryHolder(); tags() (from TypedInstance) answers the holder's tags.
 *
 * Agrees with NeoForge's 1.21.11 -> 26.1 primer (https://github.com/neoforged/.github/blob/main/primers/26.1/index.md, CC BY 4.0):
 * "ItemStack now implements TypedInstance<Item>: getItemHolder -> typeHolder, getTags -> tags".
 */
public final class ItemStack {

    private ItemStack() {}

    public static net.minecraft.world.item.ItemStack init(ItemLike item, int count, PatchedDataComponentMap components) {
        return new net.minecraft.world.item.ItemStack(item.asItem().builtInRegistryHolder(), count, components);
    }

    public static Stream getTags(net.minecraft.world.item.ItemStack self) {
        return self.tags();
    }
}
