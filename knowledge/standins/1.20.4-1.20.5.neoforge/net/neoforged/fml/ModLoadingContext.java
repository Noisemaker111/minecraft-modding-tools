package mmt.standin.net.neoforged.fml;

import java.util.function.Supplier;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * NeoForge 20.4's ModLoadingContext (FancyModLoader 2.0.17) on 20.5 (FancyModLoader 3.0.18).
 *
 * Read from both loaders' files: registerExtensionPoint(point, supplier) keeps the supplier as the active mod's
 * extension under the class it is given, in both. 20.4's mods list read a config screen under
 * ConfigScreenHandler.ConfigScreenFactory.class; 20.5's reads it under IConfigScreenFactory.class. So a config screen
 * factory (the stand-in record, itself an IConfigScreenFactory) is kept under IConfigScreenFactory.class; every other
 * extension point is kept as given.
 *
 * Loader files only: NeoForge's game primer covers vanilla classes, not FancyModLoader.
 */
public final class ModLoadingContext {

    private ModLoadingContext() {}

    public static void registerExtensionPoint(net.neoforged.fml.ModLoadingContext self, Class point, Supplier extension) {
        if (point == mmt.standin.net.neoforged.neoforge.client.ConfigScreenHandler$ConfigScreenFactory.class) {
            self.registerExtensionPoint(IConfigScreenFactory.class, extension);
        } else {
            self.registerExtensionPoint(point, extension);
        }
    }
}
