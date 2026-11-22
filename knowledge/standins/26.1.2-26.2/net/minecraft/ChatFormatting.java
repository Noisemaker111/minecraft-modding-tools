package mmt.standin.net.minecraft;

import java.util.Locale;

/**
 * 26.1.2's ChatFormatting on 26.2.
 *
 * Read from both releases: 26.1.2's ChatFormatting kept, for each constant, its code character, its name, an id (0 to 15
 * for the sixteen colors in order, -1 for the formats and RESET), whether it is a format (OBFUSCATED, BOLD, STRIKETHROUGH,
 * UNDERLINE, ITALIC) and the color's RGB value (null for the formats and RESET); getChar, getName (the constant's name in
 * lower case), getId, isFormat, isColor (neither a format nor RESET), getColor, getById (RESET for a negative id, the
 * constant with that id, or null) and getByName (the name lower-cased, letters only) read them. 26.2 keeps the constants in
 * the same order with only their code (toString() is the section sign and the code) and drops the rest, styles taking
 * their colors from TextColor. So each old member answers from the constant's place and 26.1.2's own table of colors.
 *
 * Agrees with NeoForge's 26.1.2 -> 26.2 primer (https://github.com/neoforged/.github/blob/main/primers/26.2/index.md, CC BY 4.0): "ChatFormatting has been mostly gutted in favor of using Style", "getChar is removed", "getId, getName replaced by TextColor#serialize, not one-to-one", "isColor, getColor replaced by TextColor#getValue, not one-to-one", "getByName, getById replaced by TextColor#parseColor, not one-to-one".
 */
public final class ChatFormatting {

    private static final int COLORS = 16;
    private static final int[] RGB = {0, 170, 43520, 43690, 11141120, 11141290, 16755200, 11184810, 5592405, 5592575, 5635925, 5636095, 16733525, 16733695, 16777045, 16777215};

    private ChatFormatting() {}

    public static char getChar(net.minecraft.ChatFormatting self) {
        return self.toString().charAt(1);
    }

    public static String getName(net.minecraft.ChatFormatting self) {
        return self.name().toLowerCase(Locale.ROOT);
    }

    public static int getId(net.minecraft.ChatFormatting self) {
        return self.ordinal() < COLORS ? self.ordinal() : -1;
    }

    public static boolean isFormat(net.minecraft.ChatFormatting self) {
        return self.ordinal() >= COLORS && self != net.minecraft.ChatFormatting.RESET;
    }

    public static boolean isColor(net.minecraft.ChatFormatting self) {
        return self.ordinal() < COLORS;
    }

    public static Integer getColor(net.minecraft.ChatFormatting self) {
        return self.ordinal() < COLORS ? Integer.valueOf(RGB[self.ordinal()]) : null;
    }

    public static net.minecraft.ChatFormatting getById(int id) {
        if (id < 0) {
            return net.minecraft.ChatFormatting.RESET;
        }
        return id < COLORS ? net.minecraft.ChatFormatting.values()[id] : null;
    }

    public static net.minecraft.ChatFormatting getByName(String name) {
        if (name == null) {
            return null;
        }
        String wanted = clean(name);
        for (net.minecraft.ChatFormatting format : net.minecraft.ChatFormatting.values()) {
            if (clean(format.name()).equals(wanted)) {
                return format;
            }
        }
        return null;
    }

    private static String clean(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }
}
