package mmt.standin.net.minecraft.world.item.enchantment;

/**
 * 1.21's Enchantments on 1.20.6, for a port back: each enchantment's registry key.
 *
 * Read from both releases: 1.21's Enchantments holds only keys, ResourceKey.create(Registries.ENCHANTMENT,
 * minecraft:ID), for enchantments a data pack defines; 1.20.6's holds the enchantments themselves, registered in
 * BuiltInRegistries.ENCHANTMENT under the same ids (all 42, protection to wind_burst), which
 * Registries.ENCHANTMENT names in both. So a key made here finds 1.20.6's enchantment where 1.21's finds the data
 * pack's, through the registry access either way.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`Enchantment`s are now a datapack registry object. Querying them requires access to a `HolderLookup.Provider` or one of its subclasses."
 */
public final class Enchantments {

    private Enchantments() {}

    static net.minecraft.resources.ResourceKey key(String id) {
        return net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT, new net.minecraft.resources.ResourceLocation(id));
    }

    public static net.minecraft.resources.ResourceKey PROTECTION() {
        return key("protection");
    }

    public static net.minecraft.resources.ResourceKey FIRE_PROTECTION() {
        return key("fire_protection");
    }

    public static net.minecraft.resources.ResourceKey FEATHER_FALLING() {
        return key("feather_falling");
    }

    public static net.minecraft.resources.ResourceKey BLAST_PROTECTION() {
        return key("blast_protection");
    }

    public static net.minecraft.resources.ResourceKey PROJECTILE_PROTECTION() {
        return key("projectile_protection");
    }

    public static net.minecraft.resources.ResourceKey RESPIRATION() {
        return key("respiration");
    }

    public static net.minecraft.resources.ResourceKey AQUA_AFFINITY() {
        return key("aqua_affinity");
    }

    public static net.minecraft.resources.ResourceKey THORNS() {
        return key("thorns");
    }

    public static net.minecraft.resources.ResourceKey DEPTH_STRIDER() {
        return key("depth_strider");
    }

    public static net.minecraft.resources.ResourceKey FROST_WALKER() {
        return key("frost_walker");
    }

    public static net.minecraft.resources.ResourceKey BINDING_CURSE() {
        return key("binding_curse");
    }

    public static net.minecraft.resources.ResourceKey SOUL_SPEED() {
        return key("soul_speed");
    }

    public static net.minecraft.resources.ResourceKey SWIFT_SNEAK() {
        return key("swift_sneak");
    }

    public static net.minecraft.resources.ResourceKey SHARPNESS() {
        return key("sharpness");
    }

    public static net.minecraft.resources.ResourceKey SMITE() {
        return key("smite");
    }

    public static net.minecraft.resources.ResourceKey BANE_OF_ARTHROPODS() {
        return key("bane_of_arthropods");
    }

    public static net.minecraft.resources.ResourceKey KNOCKBACK() {
        return key("knockback");
    }

    public static net.minecraft.resources.ResourceKey FIRE_ASPECT() {
        return key("fire_aspect");
    }

    public static net.minecraft.resources.ResourceKey LOOTING() {
        return key("looting");
    }

    public static net.minecraft.resources.ResourceKey SWEEPING_EDGE() {
        return key("sweeping_edge");
    }

    public static net.minecraft.resources.ResourceKey EFFICIENCY() {
        return key("efficiency");
    }

    public static net.minecraft.resources.ResourceKey SILK_TOUCH() {
        return key("silk_touch");
    }

    public static net.minecraft.resources.ResourceKey UNBREAKING() {
        return key("unbreaking");
    }

    public static net.minecraft.resources.ResourceKey FORTUNE() {
        return key("fortune");
    }

    public static net.minecraft.resources.ResourceKey POWER() {
        return key("power");
    }

    public static net.minecraft.resources.ResourceKey PUNCH() {
        return key("punch");
    }

    public static net.minecraft.resources.ResourceKey FLAME() {
        return key("flame");
    }

    public static net.minecraft.resources.ResourceKey INFINITY() {
        return key("infinity");
    }

    public static net.minecraft.resources.ResourceKey LUCK_OF_THE_SEA() {
        return key("luck_of_the_sea");
    }

    public static net.minecraft.resources.ResourceKey LURE() {
        return key("lure");
    }

    public static net.minecraft.resources.ResourceKey LOYALTY() {
        return key("loyalty");
    }

    public static net.minecraft.resources.ResourceKey IMPALING() {
        return key("impaling");
    }

    public static net.minecraft.resources.ResourceKey RIPTIDE() {
        return key("riptide");
    }

    public static net.minecraft.resources.ResourceKey CHANNELING() {
        return key("channeling");
    }

    public static net.minecraft.resources.ResourceKey MULTISHOT() {
        return key("multishot");
    }

    public static net.minecraft.resources.ResourceKey QUICK_CHARGE() {
        return key("quick_charge");
    }

    public static net.minecraft.resources.ResourceKey PIERCING() {
        return key("piercing");
    }

    public static net.minecraft.resources.ResourceKey DENSITY() {
        return key("density");
    }

    public static net.minecraft.resources.ResourceKey BREACH() {
        return key("breach");
    }

    public static net.minecraft.resources.ResourceKey WIND_BURST() {
        return key("wind_burst");
    }

    public static net.minecraft.resources.ResourceKey MENDING() {
        return key("mending");
    }

    public static net.minecraft.resources.ResourceKey VANISHING_CURSE() {
        return key("vanishing_curse");
    }
}
