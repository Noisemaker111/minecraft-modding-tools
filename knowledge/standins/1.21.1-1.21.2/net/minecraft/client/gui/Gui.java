package mmt.standin.net.minecraft.client.gui;

import net.minecraft.resources.ResourceLocation;

/**
 * 1.21.1's Gui.PUMPKIN_BLUR_LOCATION (private, opened by mods' access transformers) on 1.21.2.
 *
 * Read from both releases: 1.21.1's Gui drew textures/misc/pumpkinblur.png over the screen while the player wore a carved
 * pumpkin, from this constant. 1.21.2 draws whatever camera overlay the worn item's EQUIPPABLE component names, and the
 * carved pumpkin's is misc/pumpkinblur, which Gui.renderCameraOverlays turns into textures/misc/pumpkinblur.png: the same
 * texture, now with no constant. So it is that location.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "Equippable, which used to be an interface, is now a data component that contains how an entity can equip this item and if the equipment should be rendered"; the texture is from the game's code.
 */
public final class Gui {

    private Gui() {}

    public static ResourceLocation PUMPKIN_BLUR_LOCATION() {
        return ResourceLocation.withDefaultNamespace("textures/misc/pumpkinblur.png");
    }
}
