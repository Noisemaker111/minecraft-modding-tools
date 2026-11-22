package mmt.standin.net.minecraft.network.chat;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

/**
 * 1.21.11's new HoverEvent.ShowItem(ItemStack) on 26.1.
 *
 * Read from both releases: 1.21.11's ShowItem was a record holding the stack it was given (copied). 26.1's holds an
 * ItemStackTemplate, the item, count and component patch without the stack, made from a stack by
 * ItemStackTemplate.fromNonEmptyStack, and gives the stack back with item().create() (the route learned that for
 * item()). The game builds an item hover only from a stack that is not empty (ItemStack.getDisplayName), and 26.1 has
 * no template of an empty one, so an empty stack fails here as fromNonEmptyStack fails in the game. This also carries
 * 1.21.5's HoverEvent stand-in, which builds a ShowItem from a stack, onto 26.1.
 *
 * From the two releases' code alone (26.1's ShowItem, ItemStackTemplate and ItemStack.getDisplayName); no migration
 * note quoted.
 */
public final class HoverEvent$ShowItem {

    private HoverEvent$ShowItem() {}

    public static net.minecraft.network.chat.HoverEvent.ShowItem init(ItemStack stack) {
        return new net.minecraft.network.chat.HoverEvent.ShowItem(ItemStackTemplate.fromNonEmptyStack(stack));
    }
}
