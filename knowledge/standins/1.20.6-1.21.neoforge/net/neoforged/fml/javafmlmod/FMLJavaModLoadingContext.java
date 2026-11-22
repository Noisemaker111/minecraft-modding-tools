package mmt.standin.net.neoforged.fml.javafmlmod;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingContext;

/**
 * FancyModLoader 3.0.45's FMLJavaModLoadingContext (NeoForge 20.6), which 4.0.23 (NeoForge 21.0) dropped.
 *
 * Read from both loaders' files: 3.0.45's FMLJavaModLoadingContext.get() was the active mod container's language
 * extension, and getModEventBus() that container's event bus (FMLModContainer.getEventBus()). 4.0.23 removed the class
 * and its extension; ModLoadingContext.get().getActiveContainer() and ModContainer.getEventBus() give the same container
 * and bus.
 *
 * Loader files only: NeoForge's game primer covers vanilla classes; 3.0.45 marked the class @Deprecated(forRemoval = true).
 */
public class FMLJavaModLoadingContext {

    private final ModContainer container;

    private FMLJavaModLoadingContext(ModContainer container) {
        this.container = container;
    }

    public static FMLJavaModLoadingContext get() {
        return new FMLJavaModLoadingContext(ModLoadingContext.get().getActiveContainer());
    }

    public IEventBus getModEventBus() {
        return this.container.getEventBus();
    }
}
