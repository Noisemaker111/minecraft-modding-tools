package mmt.standin.net.minecraft.network.chat.contents;

/**
 * 1.19.4's TranslatableContents.getFallback() on 1.19.3, for a port back.
 *
 * Read from both releases: 1.19.4 keeps a nullable fallback beside the key, null for every text made without one
 * (translatable(key), the game's own text, text read from JSON without a "fallback" field). 1.19.3's contents have no
 * fallback, which is 1.19.4's null.
 *
 * Not the same: text a ported mod made with translatableWithFallback while the language lacked the key holds the
 * fallback as its key here (see the Component stand-in), so asking it for its fallback gives null and its key the
 * fallback text.
 */
public final class TranslatableContents {

    private TranslatableContents() {
    }

    public static String getFallback(net.minecraft.network.chat.contents.TranslatableContents self) {
        return null;
    }
}
