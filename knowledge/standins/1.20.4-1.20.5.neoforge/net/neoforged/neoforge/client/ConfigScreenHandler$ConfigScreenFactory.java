package mmt.standin.net.neoforged.neoforge.client;

import java.util.function.BiFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * NeoForge 20.4's ConfigScreenHandler.ConfigScreenFactory (20.4.253) on 20.5 (20.5.21-beta).
 *
 * Read from both loaders' files: 20.4's ConfigScreenFactory was a record of a function from the game and the parent
 * screen to a mod's config screen, registered as the mod's extension point under ConfigScreenFactory.class and read by
 * the mods list (ConfigScreenHandler.getScreenFactoryFor). 20.5 removed it for IConfigScreenFactory, an extension point
 * whose createScreen(minecraft, parent) makes the same screen, read under IConfigScreenFactory.class
 * (IConfigScreenFactory.getForMod). The record as it was, and an IConfigScreenFactory making the screen with its
 * function; ModLoadingContext's stand-in registers it under IConfigScreenFactory.class.
 *
 * Loader files only: NeoForge's game primer covers vanilla classes, not NeoForge's own API.
 */
public record ConfigScreenHandler$ConfigScreenFactory(BiFunction screenFunction) implements IConfigScreenFactory {

    @Override
    public Screen createScreen(Minecraft minecraft, Screen parent) {
        return (Screen) this.screenFunction.apply(minecraft, parent);
    }
}
