package mmt.standin.net.minecraft.nbt;

import net.minecraft.nbt.StringTag;

/**
 * 1.21.4's Tag.getAsString() on 1.21.5.
 *
 * Read from both releases: 1.21.4's getAsString() answered a string tag's own text and, for every other tag, its text
 * form written by a StringTagVisitor (the same text the tag's toString() gave). 1.21.5 made StringTag a record (value()
 * is its text) and turned getAsString into asString(), an Optional present only for string tags; every tag's toString()
 * still writes it with a StringTagVisitor. So a string tag answers its value and any other its toString().
 *
 * Agrees with NeoForge's 1.21.4 -> 1.21.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.5/index.md, CC BY 4.0):
 * "getAsString -> asString, not one-to-one", "StringTag is now a record".
 */
public final class Tag {

    private Tag() {}

    public static String getAsString(net.minecraft.nbt.Tag self) {
        if (self instanceof StringTag string) {
            return string.value();
        }
        return self.toString();
    }
}
