package mmt.standin.net.minecraft.network.chat;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.MutableComponent;

/**
 * 1.19.4's Component.translatableWithFallback(key, fallback[, args]) on 1.19.3, for a port back.
 *
 * Read from both releases: 1.19.4 gave TranslatableContents a fallback, the text it formats (with the same arguments)
 * where the language has no entry for the key. 1.19.3's TranslatableContents has none: where the language lacks a key
 * it formats the key itself as the text. So a key the language has is 1.19.3's translatable(key, args), and one it lacks
 * is translatable(fallback, args), which formats the fallback with the arguments as 1.19.4 does (the fallback being no
 * entry of the language, as 1.19.4 means it).
 *
 * Not the same: 1.19.4 asks the language each time it changes; here the language at the time the text is made
 * decides, so text made before a language that has the key is loaded keeps its fallback. A null fallback is
 * translatable(key, args), as in 1.19.4.
 */
public final class Component {

    private Component() {
    }

    public static MutableComponent translatableWithFallback(String key, String fallback) {
        return translatableWithFallback(key, fallback, new Object[0]);
    }

    public static MutableComponent translatableWithFallback(String key, String fallback, Object... args) {
        if (fallback == null || Language.getInstance().has(key)) {
            return net.minecraft.network.chat.Component.translatable(key, args);
        }
        return net.minecraft.network.chat.Component.translatable(fallback, args);
    }
}
