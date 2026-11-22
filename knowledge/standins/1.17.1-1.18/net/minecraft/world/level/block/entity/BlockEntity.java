package mmt.standin.net.minecraft.world.level.block.entity;

import net.minecraft.nbt.CompoundTag;

/**
 * 1.17.1's BlockEntity.save(CompoundTag) on 1.18.
 *
 * Read from both releases: 1.17.1's save(tag) wrote the block entity's data into the tag it was given (each subclass
 * wrote its own and called super.save), added the id and the x, y, z of its position, and returned that tag. 1.18 split
 * it: the data goes through the protected saveAdditional(tag), and saveWithFullMetadata() returns a new tag holding the
 * data, the id and the position (saveWithoutMetadata() and saveWithId() hold less). This writes what
 * saveWithFullMetadata() returns into the tag the caller gave and returns that tag, as 1.17.1 did.
 *
 * Fabric's 1.18 post (https://fabricmc.net/2021/11/30/118.html) does not mention block entity saving; this is from the
 * game's code alone.
 */
public final class BlockEntity {

    private BlockEntity() {}

    public static CompoundTag save(net.minecraft.world.level.block.entity.BlockEntity self, CompoundTag tag) {
        return tag.merge(self.saveWithFullMetadata());
    }
}
