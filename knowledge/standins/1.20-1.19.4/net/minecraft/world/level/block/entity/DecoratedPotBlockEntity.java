package mmt.standin.net.minecraft.world.level.block.entity;

import java.util.List;
import net.minecraft.world.item.Item;

/**
 * 1.20's DecoratedPotBlockEntity.getDecorations() on 1.19.4.
 *
 * Read from both releases: 1.20 keeps a pot's four sherds as one Decorations value (back, left, right, front); 1.19.4
 * keeps the same four items as a list (getShards()), read by its DecoratedPotRenderer as back = 0, left = 1, right = 2,
 * front = 3, the order 1.20's Decorations.sorted() writes them in. The value made here is the list's items in place.
 */
public final class DecoratedPotBlockEntity {

    private DecoratedPotBlockEntity() {
    }

    public static DecoratedPotBlockEntity$Decorations getDecorations(net.minecraft.world.level.block.entity.DecoratedPotBlockEntity self) {
        List<Item> shards = self.getShards();
        return new DecoratedPotBlockEntity$Decorations(shards.get(0), shards.get(1), shards.get(2), shards.get(3));
    }
}
