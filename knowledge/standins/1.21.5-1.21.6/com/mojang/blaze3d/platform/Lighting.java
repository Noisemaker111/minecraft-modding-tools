package mmt.standin.com.mojang.blaze3d.platform;

import net.minecraft.client.Minecraft;

/**
 * 1.21.5's Lighting on 1.21.6.
 *
 * Read from both releases: 1.21.5's static Lighting.setupForEntityInInventory() set the shader lights to the inventory
 * entity directions (INVENTORY_DIFFUSE_LIGHT_0 and _1) for the draws after it. 1.21.6's Lighting is an instance the game
 * renderer owns (GameRenderer.getLighting()), holding one light buffer per use; setupFor(Lighting.Entry.ENTITY_IN_UI)
 * selects the entity-in-inventory lights, as InventoryScreen's entity rendering does.
 *
 * Agrees with NeoForge's 1.21.5 -> 1.21.6 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.6/index.md, CC BY 4.0): "setup* -> setupFor, an instance method that takes in an $Entry", GameRenderer "getLighting - Gets the lighting renderer".
 */
public final class Lighting {

    private Lighting() {}

    public static void setupForEntityInInventory() {
        Minecraft.getInstance().gameRenderer.getLighting().setupFor(com.mojang.blaze3d.platform.Lighting.Entry.ENTITY_IN_UI);
    }
}
