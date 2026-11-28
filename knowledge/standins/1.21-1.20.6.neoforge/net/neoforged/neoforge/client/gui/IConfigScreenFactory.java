package mmt.standin.net.neoforged.neoforge.client.gui;

/**
 * NeoForge 21.0's IConfigScreenFactory.createScreen(container, modListScreen), called by a mod, on NeoForge 20.6's
 * createScreen(minecraft, modListScreen), for a port back: the factory runs with that container as the one it opens
 * (see the stand-in ModContainer, which reads both loaders' factories).
 */
public final class IConfigScreenFactory {

    private IConfigScreenFactory() {}

    public static net.minecraft.client.gui.screens.Screen createScreen(net.neoforged.neoforge.client.gui.IConfigScreenFactory self, net.neoforged.fml.ModContainer container, net.minecraft.client.gui.screens.Screen parent) {
        return mmt.standin.net.neoforged.fml.ModContainer.Opening.screen(container, self, net.minecraft.client.Minecraft.getInstance(), parent);
    }
}
