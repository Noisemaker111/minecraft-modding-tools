package mmt.standin.net.minecraft.nbt;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * 1.20.4's NbtUtils on 1.20.5.
 *
 * Read from both releases: 1.20.4's readGameProfile(tag) read a profile from the tag form its writeGameProfile wrote:
 * Id (a UUID as 4 ints, else the nil UUID), Name, and Properties, a compound of lists of {Value, Signature?} under
 * each property name; anything that threw (a profile authlib refuses) gave null. 1.20.5 removed both for
 * ExtraCodecs.GAME_PROFILE, which reads another form (id, name, properties as a list), so a tag a mod wrote the old way
 * is read here the old way, with 1.20.5's authlib GameProfile and Property, which are unchanged.
 *
 * Agrees with NeoForge's 1.20.4 -> 1.20.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.20.5/index.md, CC BY 4.0): "net.minecraft.nbt.NbtUtils#readGameProfile, #writeGameProfile -> ExtraCodecs#GAME_PROFILE".
 */
public final class NbtUtils {

    private NbtUtils() {}

    public static GameProfile readGameProfile(CompoundTag tag) {
        java.util.UUID id = tag.hasUUID("Id") ? tag.getUUID("Id") : Util.NIL_UUID;
        String name = tag.getString("Name");
        try {
            GameProfile profile = new GameProfile(id, name);
            if (tag.contains("Properties", Tag.TAG_COMPOUND)) {
                CompoundTag properties = tag.getCompound("Properties");
                for (Object each : properties.getAllKeys()) {
                    String key = (String) each;
                    ListTag list = properties.getList(key, Tag.TAG_COMPOUND);
                    for (int i = 0; i < list.size(); i++) {
                        CompoundTag property = list.getCompound(i);
                        String value = property.getString("Value");
                        if (property.contains("Signature", Tag.TAG_STRING)) {
                            profile.getProperties().put(key, new Property(key, value, property.getString("Signature")));
                        } else {
                            profile.getProperties().put(key, new Property(key, value));
                        }
                    }
                }
            }
            return profile;
        } catch (Throwable unreadable) {
            return null;
        }
    }
}
