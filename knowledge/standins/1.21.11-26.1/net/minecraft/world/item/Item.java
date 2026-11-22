package mmt.standin.net.minecraft.world.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * 1.21.11's Item on 26.1.
 *
 * Read from both releases: 1.21.11's final Item.getName() returned the item's own default name,
 * components.getOrDefault(DataComponents.ITEM_NAME, CommonComponents.EMPTY), beside getName(ItemStack) for a stack's. 26.1
 * keeps getName(ItemStack) and the item's default components (Item.components()) and drops getName(). So the old method
 * reads the same component from the item's own components.
 *
 * Agrees with NeoForge's 1.21.11 -> 26.1 primer (https://github.com/neoforged/.github/blob/main/primers/26.1/index.md, CC BY 4.0), whose list of removals names "Item#getName()".
 */
public final class Item {

    private Item() {}

    public static Component getName(net.minecraft.world.item.Item self) {
        return (Component) self.components().getOrDefault(DataComponents.ITEM_NAME, CommonComponents.EMPTY);
    }
}
