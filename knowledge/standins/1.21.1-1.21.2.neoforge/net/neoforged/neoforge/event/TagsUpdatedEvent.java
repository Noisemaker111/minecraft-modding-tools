package mmt.standin.net.neoforged.neoforge.event;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;

/**
 * NeoForge 21.1's TagsUpdatedEvent.getRegistryAccess() on NeoForge 21.2 (Minecraft 1.21.2).
 *
 * Read from both releases: 21.1 posted the event with a RegistryAccess (the client's registry access when its tags
 * arrived; ReloadableServerResources' full registry access when the server's tags were applied). 21.2 posts it with a
 * HolderLookup.Provider (getLookupProvider()): the client still passes its RegistryAccess, and the server passes the
 * reload's lookupWithUpdatedTags, which ReloadableServerRegistries joins from each registry's lookup with the new tags
 * (MappedRegistry.prepareTagReload's lookup, a HolderLookup.RegistryLookup.Delegate whose parent() is the registry) and
 * the reloaded registries themselves. So the old access is the provider when it is one, else an access over the registries
 * behind each of its lookups, which hold those tags once applied, as the event is posted after.
 *
 * NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0) covers the game, not this event; this is from
 * NeoForge 21.1.62's and 21.2.1-beta's own sources and patches (ReloadableServerResources, ClientPacketListener).
 */
public final class TagsUpdatedEvent {

    private TagsUpdatedEvent() {}

    public static RegistryAccess getRegistryAccess(net.neoforged.neoforge.event.TagsUpdatedEvent self) {
        HolderLookup.Provider provider = self.getLookupProvider();
        if (provider instanceof RegistryAccess access) {
            return access;
        }
        List<Registry> registries = new ArrayList<>();
        provider.listRegistries().forEach(o -> {
            Object lookup = o;
            while (lookup instanceof HolderLookup.RegistryLookup.Delegate delegate) {
                lookup = delegate.parent();
            }
            if (lookup instanceof Registry registry) {
                registries.add(registry);
            }
        });
        return new RegistryAccess.ImmutableRegistryAccess(registries).freeze();
    }
}
