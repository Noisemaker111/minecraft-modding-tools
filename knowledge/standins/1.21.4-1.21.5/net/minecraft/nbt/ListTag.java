package mmt.standin.net.minecraft.nbt;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * 1.21.4's ListTag on 1.21.5.
 *
 * Read from both releases: 1.21.4's getX(i) gave the element at i when it was exactly that tag type and otherwise a
 * default (0, an empty array, a new compound or list), and getString(i) gave a string element's value or any other
 * element's toString(), "" out of range. 1.21.5's getters return Optionals and its getXOr(i, default) take any number
 * for a number type, so these check the exact type as 1.21.4 did.
 *
 * Agrees with NeoForge's 1.21.4 -> 1.21.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.5/index.md, CC BY 4.0): ListTag "get*, get*Or - Returns an optional wrapped object for the key, or the default value specified is using the Or methods".
 */
public final class ListTag {

    private ListTag() {}

    private static Tag at(net.minecraft.nbt.ListTag self, int index) {
        return index >= 0 && index < self.size() ? self.get(index) : null;
    }

    public static CompoundTag getCompound(net.minecraft.nbt.ListTag self, int index) {
        return at(self, index) instanceof CompoundTag tag ? tag : new CompoundTag();
    }

    public static net.minecraft.nbt.ListTag getList(net.minecraft.nbt.ListTag self, int index) {
        return at(self, index) instanceof net.minecraft.nbt.ListTag tag ? tag : new net.minecraft.nbt.ListTag();
    }

    public static short getShort(net.minecraft.nbt.ListTag self, int index) {
        return at(self, index) instanceof ShortTag tag ? tag.shortValue() : 0;
    }

    public static int[] getIntArray(net.minecraft.nbt.ListTag self, int index) {
        return at(self, index) instanceof IntArrayTag tag ? tag.getAsIntArray() : new int[0];
    }

    public static long[] getLongArray(net.minecraft.nbt.ListTag self, int index) {
        return at(self, index) instanceof LongArrayTag tag ? tag.getAsLongArray() : new long[0];
    }

    public static double getDouble(net.minecraft.nbt.ListTag self, int index) {
        return at(self, index) instanceof DoubleTag tag ? tag.doubleValue() : 0.0;
    }

    public static float getFloat(net.minecraft.nbt.ListTag self, int index) {
        return at(self, index) instanceof FloatTag tag ? tag.floatValue() : 0.0F;
    }

    public static String getString(net.minecraft.nbt.ListTag self, int index) {
        Tag tag = at(self, index);
        if (tag == null) {
            return "";
        }
        return tag instanceof StringTag string ? string.value() : tag.toString();
    }
}
