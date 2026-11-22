package mmt.standin.net.neoforged.fml.loading;

import net.neoforged.fml.Bindings;
import net.neoforged.fml.IBindingsProvider;

/**
 * NeoForge 21.1's FMLLoader (FancyModLoader 4.0.45) on 21.2 (21.2.1-beta, FancyModLoader 4.0.29).
 *
 * Read from both loaders' files: 4.0.45's FMLLoader.getBindings() loaded the one IBindingsProvider service from the game
 * layer and kept it; its only method is getGameBus(). 4.0.29 has no getBindings(): net.neoforged.fml.Bindings loads the
 * same service the same way and offers getGameBus() itself. A provider that asks Bindings is the same provider.
 *
 * Loader files only: NeoForge's game primer covers vanilla classes, not FancyModLoader.
 */
public final class FMLLoader {

    private FMLLoader() {}

    public static IBindingsProvider getBindings() {
        return Bindings::getGameBus;
    }
}
