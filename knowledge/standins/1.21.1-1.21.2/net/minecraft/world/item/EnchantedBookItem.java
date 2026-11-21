package mmt.standin.net.minecraft.world.item;

import net.minecraft.world.item.Items;

/**
 * 1.21.1's EnchantedBookItem, gone in 1.21.2.
 *
 * Read from both releases: in 1.21.1 Items.ENCHANTED_BOOK was registered as new EnchantedBookItem(properties) and no other
 * game item is one. 1.21.2 registers it as a plain Item whose properties carry its stored enchantments
 * (DataComponents.STORED_ENCHANTMENTS) and glint, so "is an EnchantedBookItem" is "is Items.ENCHANTED_BOOK".
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "BookItem, EnchantedBookItem -> DataComponents#WRITTEN_BOOK_CONTENT" (and the 1.21.2 game's Items gives the book its stored enchantments as a component).
 */
public final class EnchantedBookItem {

    private EnchantedBookItem() {}

    public static boolean isInstance(Object value) {
        return value == Items.ENCHANTED_BOOK;
    }
}
