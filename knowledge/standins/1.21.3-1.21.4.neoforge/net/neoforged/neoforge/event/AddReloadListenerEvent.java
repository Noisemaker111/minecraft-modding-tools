package mmt.standin.net.neoforged.neoforge.event;

import java.util.Locale;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;

/**
 * NeoForge 21.3's AddReloadListenerEvent (21.3.100) on 21.4 (21.4.161).
 *
 * Read from both loaders' files: 21.3's addListener(listener) added the listener to the server's reload, after
 * vanilla's, in a wrapper that passed injectContext on to a ContextAwareReloadListener and skipped the reload when
 * mod loading had failed. 21.4 replaced the event with AddServerReloadListenersEvent, whose addListener(key, listener)
 * registers the listener itself (no wrapper: the listeners themselves are handed the context) under a unique key, and
 * runs every listener that names no dependency after the last vanilla listener in the order added (ReloadListenerSort).
 * The key is made from the listener's class name (made unique when the class registers more than one).
 *
 * Loader files only: NeoForge's game primer covers vanilla classes, not NeoForge's own events.
 */
public final class AddReloadListenerEvent {

    private AddReloadListenerEvent() {}

    public static void addListener(AddServerReloadListenersEvent self, PreparableReloadListener listener) {
        String base = listener.getClass().getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
        ResourceLocation key = ResourceLocation.fromNamespaceAndPath("mmt_standin", base);
        for (int i = 2; self.getRegistry().containsKey(key); i++) {
            key = ResourceLocation.fromNamespaceAndPath("mmt_standin", base + "_" + i);
        }
        self.addListener(key, listener);
    }
}
