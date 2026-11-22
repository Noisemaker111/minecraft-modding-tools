package mmt.standin.net.neoforged.fml;

import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;

/**
 * NeoForge 20.6's ModLoadingContext (FancyModLoader 3.0.45) on 21.0 (FancyModLoader 4.0.23).
 *
 * Read from both loaders' files: 3.0.45's registerConfig(type, spec[, fileName]), deprecated for removal, called the same
 * method on getActiveContainer(). 4.0.23 drops it from ModLoadingContext and keeps ModContainer.registerConfig(type,
 * spec[, fileName]) and getActiveContainer() unchanged.
 *
 * Loader files only: NeoForge's game primer covers vanilla classes, not FancyModLoader.
 */
public final class ModLoadingContext {

    private ModLoadingContext() {}

    public static void registerConfig(net.neoforged.fml.ModLoadingContext self, ModConfig.Type type, IConfigSpec spec) {
        self.getActiveContainer().registerConfig(type, spec);
    }

    public static void registerConfig(net.neoforged.fml.ModLoadingContext self, ModConfig.Type type, IConfigSpec spec, String fileName) {
        self.getActiveContainer().registerConfig(type, spec, fileName);
    }
}
