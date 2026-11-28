package mmt.standin.net.neoforged.fml;

/**
 * NeoForge 21.0's config screen factory, handed the mod's container, on NeoForge 20.6, whose factory is handed the game
 * (FML 4.0.23 and 3.0.45), for a port back.
 *
 * Read from both releases: 21.0's IConfigScreenFactory.createScreen(ModContainer container, Screen modListScreen) and
 * 20.6's createScreen(Minecraft minecraft, Screen modListScreen) are the same extension point, which both mod lists
 * open with the selected mod's factory (ModListScreen: f.createScreen(this.minecraft, this) on 20.6). The factory a mod
 * registers on its container is wrapped so that, while it runs, the container it was registered on is the one from(Minecraft)
 * gives the ported factory; anywhere else from(Minecraft) gives the mod being loaded (ModLoadingContext's active
 * container, as both loaders set it while a mod is constructed). The two registerExtensionPoint overloads are the same
 * in both loaders. NeoForge's primers cover the game, not the loader; this follows the two loaders' own code.
 */
public final class ModContainer {

    private ModContainer() {}

    static final ThreadLocal<net.neoforged.fml.ModContainer> OPENING = new ThreadLocal<>();

    public static net.neoforged.fml.ModContainer from(net.minecraft.client.Minecraft minecraft) {
        net.neoforged.fml.ModContainer opening = OPENING.get();
        return opening != null ? opening : net.neoforged.fml.ModLoadingContext.get().getActiveContainer();
    }

    public static void registerExtensionPoint(net.neoforged.fml.ModContainer self, Class point, net.neoforged.fml.IExtensionPoint extension) {
        self.registerExtensionPoint(point, wrap(self, point, extension));
    }

    public static void registerExtensionPoint(net.neoforged.fml.ModContainer self, Class point, java.util.function.Supplier extension) {
        self.registerExtensionPoint(point, (java.util.function.Supplier) () -> wrap(self, point, (net.neoforged.fml.IExtensionPoint) extension.get()));
    }

    static net.neoforged.fml.IExtensionPoint wrap(net.neoforged.fml.ModContainer self, Class point, net.neoforged.fml.IExtensionPoint extension) {
        if (point != net.neoforged.neoforge.client.gui.IConfigScreenFactory.class || !(extension instanceof net.neoforged.neoforge.client.gui.IConfigScreenFactory factory)) {
            return extension;
        }
        return (net.neoforged.neoforge.client.gui.IConfigScreenFactory) (minecraft, parent) -> Opening.screen(self, factory, minecraft, parent);
    }

    /** The factory a mod's container opens: a nested class, so its methods answer no member of the old class. */
    public static final class Opening {

        private Opening() {}

        public static net.minecraft.client.gui.screens.Screen screen(net.neoforged.fml.ModContainer container, net.neoforged.neoforge.client.gui.IConfigScreenFactory factory, net.minecraft.client.Minecraft minecraft, net.minecraft.client.gui.screens.Screen parent) {
            net.neoforged.fml.ModContainer was = OPENING.get();
            OPENING.set(container);
            try {
                return factory.createScreen(minecraft, parent);
            } finally {
                OPENING.set(was);
            }
        }
    }
}
