package mmt.standin.net.minecraft.world.item;

/**
 * 1.19.4's ItemStack.sameItem and ItemStack.tagMatches on 1.20.
 *
 * Read from both releases: 1.19.4's stack.sameItem(other) was true when other is not empty and stack is other's item;
 * the static tagMatches(a, b) was true when both are empty, false when one is, else when their tags are equal (no tag on
 * a matches only no tag on b). 1.20 dropped both: isSameItem(a, b) is a.is(b.getItem()) and isSameItemSameTags(a, b) asks
 * for the same item as well as equal tags. These are 1.19.4's bodies on 1.20's ItemStack, whose getTag() is the same tag.
 *
 * Agrees with Fabric's 1.20 post (https://fabricmc.net/2023/05/25/120.html): "A couple of equality methods in `ItemStack`
 * were removed" (isItemEqual and areNbtEqual in Yarn's names), "use the static `areItemsEqual()` method instead".
 */
public final class ItemStack {

    private ItemStack() {}

    public static boolean sameItem(net.minecraft.world.item.ItemStack self, net.minecraft.world.item.ItemStack other) {
        return !other.isEmpty() && self.is(other.getItem());
    }

    public static boolean tagMatches(net.minecraft.world.item.ItemStack a, net.minecraft.world.item.ItemStack b) {
        if (a.isEmpty() && b.isEmpty()) {
            return true;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.getTag() == null && b.getTag() != null) {
            return false;
        }
        return a.getTag() == null || a.getTag().equals(b.getTag());
    }
}
