package mmt.standin.net.minecraft.util;

import net.minecraft.network.chat.Component;

/**
 * 1.21.10's OptionEnum, which 1.21.11 removed.
 *
 * Read from both releases: 1.21.10's OptionEnum was an interface for the values of an option (getId(), getKey()), whose
 * caption was getKey() translated. 1.21.11 dropped it: its option values carry their caption as a Component and the
 * game's options take them through OptionInstance.Enum directly. A mod's own values keep the interface as it was.
 *
 * Agrees with NeoForge's 1.21.10 -> 1.21.11 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.11/index.md, CC BY 4.0): "OptionEnum has been removed in favor of simply calling the OptionInstance$Enum constructor with the desired values and codec ... the translatable entry is now stored as a Component than the translation key string".
 */
public interface OptionEnum {

    int getId();

    String getKey();

    default Component getCaption() {
        return Component.translatable(this.getKey());
    }
}
