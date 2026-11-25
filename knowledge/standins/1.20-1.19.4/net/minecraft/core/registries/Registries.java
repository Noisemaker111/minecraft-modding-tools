package mmt.standin.net.minecraft.core.registries;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;

/**
 * 1.20's Registries.CREATIVE_MODE_TAB on 1.19.4.
 *
 * Read from both releases: 1.20 made creative tabs registry entries, under the key minecraft:creative_mode_tab
 * (Registries.createRegistryKey("creative_mode_tab")); 1.19.4 kept them in CreativeModeTabs' own list, with no registry.
 * The key is the same value here, so a mod's ResourceKey.create(CREATIVE_MODE_TAB, id) names its tab as on 1.20; the
 * registry behind it is BuiltInRegistries' stand-in.
 */
public final class Registries {

    private static final ResourceKey CREATIVE_MODE_TAB = ResourceKey.createRegistryKey(new ResourceLocation("creative_mode_tab"));

    private Registries() {
    }

    public static ResourceKey CREATIVE_MODE_TAB() {
        return CREATIVE_MODE_TAB;
    }
}
