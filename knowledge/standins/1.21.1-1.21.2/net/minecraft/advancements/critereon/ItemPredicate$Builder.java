package mmt.standin.net.minecraft.advancements.critereon;

import java.util.Optional;
import net.minecraft.advancements.critereon.ItemPredicate;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;

/**
 * 1.21.1's ItemPredicate.Builder.of(TagKey) on 1.21.2.
 *
 * 1.21.1's set the builder's items to BuiltInRegistries.ITEM.getOrCreateTag(tag); 1.21.2's of(HolderGetter, tag) sets
 * them to getter.getOrThrow(tag), so the old call is the new one with a getter that gives the same named set.
 * Read from both releases: 1.21.1's MappedRegistry.getOrCreateTag(key) gave the registry's named set for the tag,
 * making an unbound one when the tag was not known yet, and bound every such set when tags loaded. 1.21.2 has no such call
 * on a registry: its named sets come from a HolderGetter, the registry's own lookup (get(key), which throws before tags
 * are bound) or its registration lookup, whose getOrThrow(key) is MappedRegistry.getOrCreateTagForRegistration(key): the
 * set kept for the tag across reloads, made unbound when new, bound by the next tag load (and to empty when the
 * built-in registries freeze). So the old set is the bound one when tags are bound, else that kept set.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "All critereon builders during construction now take in a HolderGetter", naming ItemPredicate$Builder#of.
 */
public final class ItemPredicate$Builder {

    private ItemPredicate$Builder() {}

    public static ItemPredicate.Builder of(ItemPredicate.Builder self, TagKey tag) {
        HolderSet.Named set = tag(BuiltInRegistries.ITEM, tag);
        return self.of(new HolderGetter() {
            @Override
            public Optional get(ResourceKey key) {
                return BuiltInRegistries.ITEM.get(key);
            }

            @Override
            public Optional get(TagKey key) {
                return Optional.of(key.equals(tag) ? set : tag(BuiltInRegistries.ITEM, key));
            }
        }, tag);
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
