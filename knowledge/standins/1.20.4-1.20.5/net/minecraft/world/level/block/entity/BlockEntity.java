package mmt.standin.net.minecraft.world.level.block.entity;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;

/**
 * 1.20.4's BlockEntity on 1.20.5.
 *
 * Read from both releases: 1.20.4's saveWithoutMetadata() wrote the block entity's own data into a new tag, naming items
 * and the like by id from the built-in registries. 1.20.5's saveWithoutMetadata(registries) writes the same data with
 * registries for what it names (the game passes the level's registryAccess()). The block entity's level's registries
 * when it has a level, else the built-in registries 1.20.4 read.
 *
 * Agrees with NeoForge's 1.20.4 -> 1.20.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.20.5/index.md, CC BY 4.0): "saveAdditional, saveWithFullMetadata, saveWithId, saveWithoutMetadata, ... now takes in a HolderLookup$Provider".
 */
public final class BlockEntity {

    private BlockEntity() {}

    public static CompoundTag saveWithoutMetadata(net.minecraft.world.level.block.entity.BlockEntity self) {
        HolderLookup.Provider registries = self.getLevel() != null ? self.getLevel().registryAccess() : RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        return self.saveWithoutMetadata(registries);
    }
}
