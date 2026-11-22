package mmt.standin.net.minecraft.network.chat;

/**
 * 1.15.2's new Style() and its setters on 1.16.
 *
 * Read from both releases: 1.15.2's Style was mutable: new Style() was empty (every property unset, inherited from the
 * parent), and setItalic, setUnderlined, setStrikethrough, setObfuscated and setInsertion set one property (null unsets
 * it) and returned the same style. 1.16's Style is immutable: Style.EMPTY is the empty one, and withItalic, withInsertion
 * (and the private constructor, for the three properties without a with- method) return a copy with one property set,
 * null unset. These return that copy, so a chain of setters that uses each result (new Style().setItalic(true)...) ends
 * where it did; a caller that changed a style in place and kept the old reference keeps it unchanged.
 *
 * Fabric's 1.16 post (https://fabricmc.net/2020/06/23/116.html) does not mention it; this is from the game's code alone.
 */
public final class Style {

    private Style() {}

    public static net.minecraft.network.chat.Style init() {
        return net.minecraft.network.chat.Style.EMPTY;
    }

    public static net.minecraft.network.chat.Style setItalic(net.minecraft.network.chat.Style self, Boolean italic) {
        return self.withItalic(italic);
    }

    public static net.minecraft.network.chat.Style setInsertion(net.minecraft.network.chat.Style self, String insertion) {
        return self.withInsertion(insertion);
    }

    public static net.minecraft.network.chat.Style setUnderlined(net.minecraft.network.chat.Style self, Boolean underlined) {
        return new net.minecraft.network.chat.Style(self.color, self.bold, self.italic, underlined, self.strikethrough, self.obfuscated, self.clickEvent, self.hoverEvent, self.insertion, self.font);
    }

    public static net.minecraft.network.chat.Style setStrikethrough(net.minecraft.network.chat.Style self, Boolean strikethrough) {
        return new net.minecraft.network.chat.Style(self.color, self.bold, self.italic, self.underlined, strikethrough, self.obfuscated, self.clickEvent, self.hoverEvent, self.insertion, self.font);
    }

    public static net.minecraft.network.chat.Style setObfuscated(net.minecraft.network.chat.Style self, Boolean obfuscated) {
        return new net.minecraft.network.chat.Style(self.color, self.bold, self.italic, self.underlined, self.strikethrough, obfuscated, self.clickEvent, self.hoverEvent, self.insertion, self.font);
    }
}
