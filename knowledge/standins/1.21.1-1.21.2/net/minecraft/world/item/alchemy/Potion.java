package mmt.standin.net.minecraft.world.item.alchemy;

import java.util.Optional;
import net.minecraft.core.Holder;

/**
 * 1.21.1's Potion.getName(Optional<Holder<Potion>>, String prefix) on 1.21.2.
 *
 * Read from both releases: 1.21.1 gave prefix + the potion's base name, or + its registry path when it had no name, or
 * + "empty" with no potion. 1.21.2's potions always have a name (new Potion(name, effects...), Potion.name()), and
 * PotionContents.getName(prefix) builds its translation key from prefix + the potion's name() or "empty". So the old
 * string is prefix + name(), or prefix + "empty".
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "Potion name is now required", "getName -> name, not one-to-one as this is stored directly on the potion without any other processing".
 */
public final class Potion {

    private Potion() {}

    public static String getName(Optional potion, String prefix) {
        return prefix + potion.map(h -> ((net.minecraft.world.item.alchemy.Potion) ((Holder) h).value()).name()).orElse("empty");
    }
}
