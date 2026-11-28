package mmt.standin.net.minecraft.world.level.block.entity;

import java.util.stream.Stream;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * 1.20's DecoratedPotBlockEntity.Decorations on 1.19.4: the class itself, which 1.19.4 does not have.
 *
 * Read from both releases: 1.20's record of a pot's four sherds (back, left, right, front; four bricks when empty),
 * saved as one list in that order. 1.19.4 keeps the same four items as a list in the same order and saves it with
 * DecoratedPotBlockEntity.saveShards under its own tag ("shards", which 1.20 renamed "sherds"), so save and load here
 * write and read what 1.19.4's pot reads and writes: an item a mod gives these tags is a pot 1.19.4 decorates.
 *
 * Both are 1.19.4's saveShards and load as they read (each sherd's item id as a string, at most four, a brick for each
 * missing or unreadable one), written here on the tag itself rather than through a pot's block entity, so a port on
 * back to 1.19.3, which has no decorated pot, keeps the same record over the same tag.
 */
public record DecoratedPotBlockEntity$Decorations(Item back, Item left, Item right, Item front) {

    public static final DecoratedPotBlockEntity$Decorations EMPTY = new DecoratedPotBlockEntity$Decorations(Items.BRICK, Items.BRICK, Items.BRICK, Items.BRICK);

    public CompoundTag save(CompoundTag tag) {
        ListTag shards = new ListTag();
        for (Item item : new Item[] { back, left, right, front }) {
            shards.add(StringTag.valueOf(BuiltInRegistries.ITEM.getKey(item).toString()));
        }
        tag.put("shards", shards);
        return tag;
    }

    public Stream<Item> sorted() {
        return Stream.of(back, left, right, front);
    }

    public static DecoratedPotBlockEntity$Decorations load(CompoundTag tag) {
        if (tag == null || !tag.contains("shards", 9)) {
            return EMPTY;
        }
        ListTag list = tag.getList("shards", 8);
        Item[] shards = { Items.BRICK, Items.BRICK, Items.BRICK, Items.BRICK };
        for (int i = 0; i < Math.min(4, list.size()); i++) {
            Tag shard = list.get(i);
            if (shard instanceof StringTag) {
                shards[i] = (Item) BuiltInRegistries.ITEM.get(new ResourceLocation(shard.getAsString()));
            }
        }
        return new DecoratedPotBlockEntity$Decorations(shards[0], shards[1], shards[2], shards[3]);
    }
}
