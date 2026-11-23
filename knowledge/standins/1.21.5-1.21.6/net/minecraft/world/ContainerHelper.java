package mmt.standin.net.minecraft.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/**
 * 1.21.5's ContainerHelper.saveAllItems and loadAllItems on a CompoundTag, on 1.21.6.
 *
 * Read from both releases: 1.21.5's saveAllItems(tag, items, registries) put under "Items" a list holding each non-empty
 * stack saved with its "Slot" byte, and answered the tag; loadAllItems(tag, items, registries) set each slot in range from
 * that list. 1.21.6's take a ValueOutput or ValueInput instead and write and read the same list, each entry an
 * ItemStackWithSlot (the stack's fields with its "Slot" byte); a TagValueOutput made with the registries
 * (createWithContext) builds that into a CompoundTag, and TagValueInput.create(reporter, registries, tag) reads one, as
 * 1.21.6's own block entities do. Problems are discarded, as 1.21.5 skipped what it could not read.
 *
 * Agrees with NeoForge's 1.21.5 -> 1.21.6 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.6/index.md, CC BY 4.0):
 * "saveAllItems now takes in a ValueOutput instead of a CompoundTag, and no longer takes in a HolderLookup$Provider while
 * returning nothing", "loadAllItems now takes in a ValueInput instead of a CompoundTag, and no longer takes in a
 * HolderLookup$Provider".
 */
public final class ContainerHelper {

    private ContainerHelper() {}

    public static CompoundTag saveAllItems(CompoundTag tag, NonNullList items, HolderLookup.Provider registries) {
        return saveAllItems(tag, items, true, registries);
    }

    public static CompoundTag saveAllItems(CompoundTag tag, NonNullList items, boolean alwaysPut, HolderLookup.Provider registries) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        net.minecraft.world.ContainerHelper.saveAllItems(output, items, alwaysPut);
        Tag list = output.buildResult().get("Items");
        if (list != null) {
            tag.put("Items", list);
        }
        return tag;
    }

    public static void loadAllItems(CompoundTag tag, NonNullList items, HolderLookup.Provider registries) {
        net.minecraft.world.ContainerHelper.loadAllItems(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag), items);
    }
}
