package mmt.standin.net.minecraft.server;

import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * 1.21.1's ReloadableServerRegistries.Holder on 1.21.2.
 *
 * Read from both releases: 1.21.1's Holder kept the full registry access it was made with (ReloadableServerResources
 * passed the server's composite access, what MinecraftServer.registryAccess() gives) and get() returned it. 1.21.2's
 * Holder keeps only a HolderLookup.Provider and has no get(); the server's registryAccess() is still the composite access.
 * So get() is the running server's registry access (NeoForge's ServerLifecycleHooks.getCurrentServer()).
 *
 * NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0) notes only that ReloadableServerRegistries#reload returns a $LoadResult; Holder.get() is from the game's code alone.
 */
public final class ReloadableServerRegistries$Holder {

    private ReloadableServerRegistries$Holder() {}

    public static RegistryAccess.Frozen get(net.minecraft.server.ReloadableServerRegistries.Holder self) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            throw new IllegalStateException("ReloadableServerRegistries.Holder.get() needs a running server on " + "1.21.2");
        }
        return server.registryAccess();
    }
}
