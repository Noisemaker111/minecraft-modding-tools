package mmt.standin.net.minecraft.network.chat;

import net.minecraft.world.item.ItemStack;

/**
 * 1.21.4's HoverEvent.ItemStackInfo, which 1.21.5 dropped.
 *
 * Read from both releases: 1.21.4's ItemStackInfo held an item, count and component patch taken from a stack and gave
 * that stack back with getItemStack(). 1.21.5's item hover holds the stack itself (HoverEvent.ShowItem(ItemStack)), so
 * this holds a copy of the stack; HoverEvent's stand-in turns it into a ShowItem.
 *
 * Agrees with NeoForge's 1.21.4 -> 1.21.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.5/index.md, CC BY 4.0): "HoverEvent is now an interface", its item value a record holding the ItemStack.
 */
public class HoverEvent$ItemStackInfo {

    private final ItemStack stack;

    public HoverEvent$ItemStackInfo(ItemStack stack) {
        this.stack = stack.copy();
    }

    public ItemStack getItemStack() {
        return this.stack;
    }
}
