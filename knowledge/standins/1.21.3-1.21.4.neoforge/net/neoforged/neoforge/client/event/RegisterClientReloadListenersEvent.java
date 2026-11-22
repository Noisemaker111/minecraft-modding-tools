package mmt.standin.net.neoforged.neoforge.client.event;

import java.util.Locale;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;

/**
 * NeoForge 21.3's RegisterClientReloadListenersEvent (21.3.100) on 21.4 (21.4.161).
 *
 * Read from both loaders' files: 21.3's registerReloadListener(listener) registered the listener with the client's
 * resource manager, after vanilla's. 21.4 replaced the event with AddClientReloadListenersEvent, whose
 * addListener(key, listener) registers a listener under a unique key, and whose sort (ReloadListenerSort) runs every
 * listener that names no dependency after the last vanilla listener, in the order added: the same place. The key is
 * made from the listener's class name (made unique when the class registers more than one).
 *
 * Loader files only: NeoForge's game primer covers vanilla classes, not NeoForge's own events.
 */
public final class RegisterClientReloadListenersEvent {

    private RegisterClientReloadListenersEvent() {}

    public static void registerReloadListener(AddClientReloadListenersEvent self, PreparableReloadListener listener) {
        String base = listener.getClass().getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
        ResourceLocation key = ResourceLocation.fromNamespaceAndPath("mmt_standin", base);
        for (int i = 2; self.getRegistry().containsKey(key); i++) {
            key = ResourceLocation.fromNamespaceAndPath("mmt_standin", base + "_" + i);
        }
        self.addListener(key, listener);
    }
}
