package mmt.standin.net.minecraft.world.level.block.entity;

import net.minecraft.nbt.CompoundTag;

/**
 * 1.17.1's AbstractFurnaceBlockEntity.save(CompoundTag) on 1.18.
 *
 * Read from both releases: 1.17.1's furnace overrode save(tag) to write the burn and cook times, its items and the
 * recipes used into the tag after BlockEntity.save had written the id and position, and returned the tag. 1.18 writes
 * the same fields in the protected saveAdditional(tag), which BlockEntity.saveWithFullMetadata() calls on a new tag
 * before adding the id and position. This writes that tag into the caller's and returns it, as 1.17.1 did.
 *
 * Fabric's 1.18 post (https://fabricmc.net/2021/11/30/118.html) does not mention block entity saving; this is from the
 * game's code alone.
 */
public final class AbstractFurnaceBlockEntity {

    private AbstractFurnaceBlockEntity() {}

    public static CompoundTag save(net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity self, CompoundTag tag) {
        return tag.merge(self.saveWithFullMetadata());
    }
}
