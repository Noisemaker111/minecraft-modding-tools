package mmt.standin.net.minecraft.advancements.critereon;

import java.util.Optional;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;

/**
 * 1.21.1's EntityTypePredicate.of(TagKey) on 1.21.2.
 *
 * 1.21.1's made new EntityTypePredicate(BuiltInRegistries.ENTITY_TYPE.getOrCreateTag(tag)); 1.21.2's of(HolderGetter, tag)
 * makes new EntityTypePredicate(getter.getOrThrow(tag)), so the old one is the predicate over the same named set.
 * Read from both releases: 1.21.1's MappedRegistry.getOrCreateTag(key) gave the registry's named set for the tag,
 * making an unbound one when the tag was not known yet, and bound every such set when tags loaded. 1.21.2 has no such call
 * on a registry: its named sets come from a HolderGetter, the registry's own lookup (get(key), which throws before tags
 * are bound) or its registration lookup, whose getOrThrow(key) is MappedRegistry.getOrCreateTagForRegistration(key): the
 * set kept for the tag across reloads, made unbound when new, bound by the next tag load (and to empty when the
 * built-in registries freeze). So the old set is the bound one when tags are bound, else that kept set.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "All critereon builders during construction now take in a HolderGetter", naming EntityTypePredicate#of.
 */
public final class EntityTypePredicate {

    private EntityTypePredicate() {}

    public static net.minecraft.advancements.critereon.EntityTypePredicate of(TagKey tag) {
        return new net.minecraft.advancements.critereon.EntityTypePredicate(tag(BuiltInRegistries.ENTITY_TYPE, tag));
    }

    static HolderSet.Named tag(Registry registry, TagKey key) {
        try {
            Optional bound = registry.get(key);
            if (bound.isPresent()) {
                return (HolderSet.Named) bound.get();
            }
        } catch (IllegalStateException unbound) {
            // tags not bound yet: the kept set below is bound when they are
        }
        if (registry instanceof MappedRegistry mapped) {
            return mapped.getOrCreateTagForRegistration(key);
        }
        return registry.getOrThrow(key);
    }
}
