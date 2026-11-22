package mmt.standin.net.minecraft.nbt;

import net.minecraft.nbt.Tag;

/**
 * 1.21.11's NbtUtils on 26.1.
 *
 * Read from both releases: 1.21.11's NbtUtils.prettyPrint(tag) was prettyPrint(tag, false), the tag printed indented
 * without its arrays' contents spelled out. 26.1 keeps prettyPrint(tag, boolean) as it was and drops the one-argument form.
 * So the old method is prettyPrint(tag, false).
 *
 * Agrees with NeoForge's 1.21.11 -> 26.1 primer (https://github.com/neoforged/.github/blob/main/primers/26.1/index.md, CC BY 4.0), whose list of removals names "NbtUtils: prettyPrint(Tag)".
 */
public final class NbtUtils {

    private NbtUtils() {}

    public static String prettyPrint(Tag tag) {
        return net.minecraft.nbt.NbtUtils.prettyPrint(tag, false);
    }
}
