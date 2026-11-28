package mmt.standin.net.minecraft.world.item.enchantment;

/**
 * 1.21's EnchantmentInstance(Holder, int) and its enchantment holder on 1.20.6, for a port back.
 *
 * Read from both releases: both are an enchantment and a level, weighted by the enchantment's weight for enchanting
 * tables (IntrusiveBase); 1.21 holds the enchantment as a Holder from the data pack registry, 1.20.6 as the registered
 * Enchantment itself. So a holder made is its value, and the field read is 1.20.6's enchantment as the built-in
 * registry's holder (BuiltInRegistries.ENCHANTMENT.wrapAsHolder), the same one Registries.ENCHANTMENT finds there.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "All references to `Enchantment`s are now wrapped with `Holder`s."
 */
public final class EnchantmentInstance {

    private EnchantmentInstance() {}

    public static net.minecraft.world.item.enchantment.EnchantmentInstance init(net.minecraft.core.Holder enchantment, int level) {
        return new net.minecraft.world.item.enchantment.EnchantmentInstance((net.minecraft.world.item.enchantment.Enchantment) enchantment.value(), level);
    }

    public static net.minecraft.core.Holder enchantment(net.minecraft.world.item.enchantment.EnchantmentInstance self) {
        return net.minecraft.core.registries.BuiltInRegistries.ENCHANTMENT.wrapAsHolder(self.enchantment);
    }
}
