package mmt.standin.net.minecraft.world.item;

import com.mojang.logging.LogUtils;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.slf4j.Logger;

/**
 * 1.21.5's ItemStack.save and parse on 1.21.6.
 *
 * Read from both releases: 1.21.5's save(registries) refused an empty stack (IllegalStateException "Cannot encode empty
 * ItemStack") and otherwise encoded it with ItemStack.CODEC through the registries' NBT ops
 * (registries.createSerializationContext(NbtOps.INSTANCE)), throwing on failure (getOrThrow); save(registries, prefix)
 * encoded onto the given tag the same way (Codec.encode); parse(registries, tag) decoded with the same codec and ops and
 * logged "Tried to load invalid item: '{}'" for what it could not read (resultOrPartial). 1.21.6 moved saving to
 * ValueOutput and ValueInput and dropped the three methods; ItemStack.CODEC, createSerializationContext and NbtOps are
 * unchanged. So each old method makes the same calls.
 *
 * Agrees with NeoForge's 1.21.5 -> 1.21.6 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.6/index.md, CC BY 4.0): "ItemStack#parse, save are removed".
 */
public final class ItemStack {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ItemStack() {}

    public static Tag save(net.minecraft.world.item.ItemStack self, HolderLookup.Provider registries) {
        if (self.isEmpty()) {
            throw new IllegalStateException("Cannot encode empty ItemStack");
        }
        return (Tag) net.minecraft.world.item.ItemStack.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), self).getOrThrow();
    }

    public static Tag save(net.minecraft.world.item.ItemStack self, HolderLookup.Provider registries, Tag prefix) {
        if (self.isEmpty()) {
            throw new IllegalStateException("Cannot encode empty ItemStack");
        }
        return (Tag) net.minecraft.world.item.ItemStack.CODEC.encode(self, registries.createSerializationContext(NbtOps.INSTANCE), prefix).getOrThrow();
    }

    public static Optional parse(HolderLookup.Provider registries, Tag tag) {
        return net.minecraft.world.item.ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag)
            .resultOrPartial(error -> LOGGER.error("Tried to load invalid item: '{}'", error));
    }
}
