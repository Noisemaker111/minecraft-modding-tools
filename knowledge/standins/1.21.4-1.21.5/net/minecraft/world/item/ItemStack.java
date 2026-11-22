package mmt.standin.net.minecraft.world.item;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * 1.21.4's ItemStack on 1.21.5.
 *
 * Read from both releases: 1.21.4's parseOptional(registries, tag) gave EMPTY for an empty tag and otherwise
 * parse(registries, tag).orElse(EMPTY); saveOptional(registries) gave an empty compound for an empty stack and otherwise
 * save(registries, new CompoundTag()). 1.21.5 keeps parse(registries, tag) and save(registries, tag) unchanged and drops
 * the two optional forms.
 *
 * Agrees with NeoForge's 1.21.4 -> 1.21.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.5/index.md, CC BY 4.0), List of Removals: "net.minecraft.world.item.ItemStack#parseOptional, saveOptional".
 */
public final class ItemStack {

    private ItemStack() {}

    public static net.minecraft.world.item.ItemStack parseOptional(HolderLookup.Provider registries, CompoundTag tag) {
        return tag.isEmpty() ? net.minecraft.world.item.ItemStack.EMPTY : (net.minecraft.world.item.ItemStack) net.minecraft.world.item.ItemStack.parse(registries, tag).orElse(net.minecraft.world.item.ItemStack.EMPTY);
    }

    public static Tag saveOptional(net.minecraft.world.item.ItemStack self, HolderLookup.Provider registries) {
        return self.isEmpty() ? new CompoundTag() : self.save(registries, new CompoundTag());
    }
}
