package mmt.standin.net.minecraft.world.level.block.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;

/**
 * 1.20's DecoratedPotBlockEntity.Decorations on 1.19.4: the class itself, which 1.19.4 does not have.
 *
 * Read from both releases: 1.20's record of a pot's four sherds (back, left, right, front; four bricks when empty),
 * saved as one list in that order. 1.19.4 keeps the same four items as a list in the same order and saves it with
 * DecoratedPotBlockEntity.saveShards under its own tag ("shards", which 1.20 renamed "sherds"), so save and load here
 * write and read what 1.19.4's pot reads and writes: an item a mod gives these tags is a pot 1.19.4 decorates.
 */
public record DecoratedPotBlockEntity$Decorations(Item back, Item left, Item right, Item front) {

    public static final DecoratedPotBlockEntity$Decorations EMPTY = new DecoratedPotBlockEntity$Decorations(Items.BRICK, Items.BRICK, Items.BRICK, Items.BRICK);

    public CompoundTag save(CompoundTag tag) {
        DecoratedPotBlockEntity.saveShards(new ArrayList<>(List.of(back, left, right, front)), tag);
        return tag;
    }

    public Stream<Item> sorted() {
        return Stream.of(back, left, right, front);
    }

    public static DecoratedPotBlockEntity$Decorations load(CompoundTag tag) {
        if (tag == null || !tag.contains("shards", 9)) {
            return EMPTY;
        }
        DecoratedPotBlockEntity pot = new DecoratedPotBlockEntity(net.minecraft.core.BlockPos.ZERO, net.minecraft.world.level.block.Blocks.DECORATED_POT.defaultBlockState());
        pot.load(tag);
        List<Item> shards = pot.getShards();
        return new DecoratedPotBlockEntity$Decorations(shards.get(0), shards.get(1), shards.get(2), shards.get(3));
    }
}
