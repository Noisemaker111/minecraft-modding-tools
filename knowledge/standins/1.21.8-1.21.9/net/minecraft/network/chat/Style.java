package mmt.standin.net.minecraft.network.chat;

import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.21.8's Style on 1.21.9.
 *
 * Read from both releases: 1.21.8's Style named its font by the font JSON's ResourceLocation; withFont(id) set it (null for
 * the default) and getFont() gave it, or DEFAULT_FONT (minecraft:default) when none was set. 1.21.9 names the font by a
 * FontDescription, of which FontDescription.Resource(id) is the font JSON by its id (FontDescription.DEFAULT is
 * Resource(minecraft:default)); the atlas and player-head kinds are new and have no id. So a font id is its Resource, and
 * a style's font read as an id is its Resource's id, or the default's for any other kind.
 *
 * Agrees with NeoForge's 1.21.8 -> 1.21.9 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.9/index.md, CC BY 4.0): "withFont now takes in a FontDescription instead of a ResourceLocation", "getFont now returns a FontDescription", "$Resource for mapping to the FontSet (the font JSON name)".
 */
public final class Style {

    private Style() {}

    public static net.minecraft.network.chat.Style withFont(net.minecraft.network.chat.Style self, ResourceLocation font) {
        return self.withFont(font == null ? null : new FontDescription.Resource(font));
    }

    public static ResourceLocation getFont(net.minecraft.network.chat.Style self) {
        return self.getFont() instanceof FontDescription.Resource resource ? resource.id() : FontDescription.DEFAULT.id();
    }
}
