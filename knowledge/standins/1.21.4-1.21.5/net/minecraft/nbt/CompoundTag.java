package mmt.standin.net.minecraft.nbt;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;

/**
 * 1.21.4's CompoundTag on 1.21.5.
 *
 * Read from both releases: 1.21.4's typed getters answered a missing or other-typed key with a default (0, an empty
 * array, an empty list), contains(key, id) compared the key's tag id with id (99 meaning any number, ids 1 to 6),
 * getTagType gave the id or 0, getList(key, id) gave the list only when empty or of that element id, and a UUID was an
 * int array of 4 (NbtUtils.createUUID, UUIDUtil.uuidToIntArray). 1.21.5's getters return Optionals, contains(key, id),
 * getTagType and the UUID methods are gone, and lists may hold mixed tags, so a list is of an id when every element is.
 * UUIDUtil.CODEC, which 1.21.5's own code stores UUIDs with, still writes the same 4-int array.
 *
 * Agrees with NeoForge's 1.21.4 -> 1.21.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.5/index.md, CC BY 4.0): "putUUID, getUUID, hasUUID is removed", "putByteArray, putIntArray with list objects are removed", "getTagType is removed", "get*, get*Or - Returns an optional wrapped object for the key, or the default value".
 */
public final class CompoundTag {

    private CompoundTag() {}

    public static byte getTagType(net.minecraft.nbt.CompoundTag self, String key) {
        Tag tag = self.get(key);
        return tag == null ? 0 : tag.getId();
    }

    public static boolean contains(net.minecraft.nbt.CompoundTag self, String key, int id) {
        int type = getTagType(self, key);
        if (type == id) {
            return true;
        }
        return id == 99 && type >= 1 && type <= 6;
    }

    public static short getShort(net.minecraft.nbt.CompoundTag self, String key) {
        return self.get(key) instanceof NumericTag number ? number.shortValue() : 0;
    }

    public static byte[] getByteArray(net.minecraft.nbt.CompoundTag self, String key) {
        return self.get(key) instanceof ByteArrayTag array ? array.getAsByteArray() : new byte[0];
    }

    public static int[] getIntArray(net.minecraft.nbt.CompoundTag self, String key) {
        return self.get(key) instanceof IntArrayTag array ? array.getAsIntArray() : new int[0];
    }

    public static long[] getLongArray(net.minecraft.nbt.CompoundTag self, String key) {
        return self.get(key) instanceof LongArrayTag array ? array.getAsLongArray() : new long[0];
    }

    public static ListTag getList(net.minecraft.nbt.CompoundTag self, String key, int id) {
        if (self.get(key) instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).getId() != id) {
                    return new ListTag();
                }
            }
            return list;
        }
        return new ListTag();
    }

    public static void putIntArray(net.minecraft.nbt.CompoundTag self, String key, List values) {
        int[] array = new int[values.size()];
        for (int i = 0; i < array.length; i++) {
            Object value = values.get(i);
            array[i] = value == null ? 0 : (Integer) value;
        }
        self.putIntArray(key, array);
    }

    public static void putUUID(net.minecraft.nbt.CompoundTag self, String key, UUID uuid) {
        self.putIntArray(key, UUIDUtil.uuidToIntArray(uuid));
    }

    public static UUID getUUID(net.minecraft.nbt.CompoundTag self, String key) {
        Tag tag = self.get(key);
        if (!(tag instanceof IntArrayTag array)) {
            throw new IllegalArgumentException("Expected UUID-Tag to be of type " + IntArrayTag.TYPE.getName() + ", but found " + (tag == null ? "nothing" : tag.getType().getName()) + ".");
        }
        int[] ints = array.getAsIntArray();
        if (ints.length != 4) {
            throw new IllegalArgumentException("Expected UUID-Array to be of length 4, but found " + ints.length + ".");
        }
        return UUIDUtil.uuidFromIntArray(ints);
    }

    public static boolean hasUUID(net.minecraft.nbt.CompoundTag self, String key) {
        return self.get(key) instanceof IntArrayTag array && array.getAsIntArray().length == 4;
    }
}
