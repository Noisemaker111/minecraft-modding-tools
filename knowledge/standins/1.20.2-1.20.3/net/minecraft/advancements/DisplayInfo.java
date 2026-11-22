package mmt.standin.net.minecraft.advancements;

import net.minecraft.resources.ResourceLocation;

/**
 * 1.20.2's DisplayInfo on 1.20.3.
 *
 * Read from both releases: 1.20.2's getBackground() returned the advancement tab's background texture, null for an
 * advancement that names none. 1.20.3 returns it as an Optional, empty where 1.20.2 returned null. So the old method is
 * the Optional's value or null.
 *
 * NeoForge's primers begin at 1.20.4 -> 1.20.5, and Fabric's 1.20.3 post (https://fabricmc.net/2023/11/30/1203.html)
 * does not mention advancements; this is from the game's code alone.
 */
public final class DisplayInfo {

    private DisplayInfo() {}

    public static ResourceLocation getBackground(net.minecraft.advancements.DisplayInfo self) {
        return (ResourceLocation) self.getBackground().orElse(null);
    }
}
