package mmt.standin.net.minecraft.world.item.enchantment;

/**
 * 1.21's Enchantment.description() on 1.20.6, for a port back: the enchantment's name.
 *
 * Read from both releases: 1.21's Enchantment is a record of its data, whose description is the name component its data
 * gives (vanilla's: a translatable "enchantment.minecraft.ID"), shown by getFullname before the level. 1.20.6's
 * Enchantment is a registered class whose getFullname starts with Component.translatable(getDescriptionId()), the same
 * "enchantment.NAMESPACE.ID".
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "// A component containing the description of the enchantment".
 */
public final class Enchantment {

    private Enchantment() {}

    public static net.minecraft.network.chat.Component description(net.minecraft.world.item.enchantment.Enchantment self) {
        return net.minecraft.network.chat.Component.translatable(self.getDescriptionId());
    }
}
