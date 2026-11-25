package mmt.standin.net.fabricmc.fabric.api.itemgroup.v1;

import net.minecraft.world.item.CreativeModeTab;

/**
 * Fabric API 1.20's FabricItemGroup.builder() on 1.19.4.
 *
 * Read from both releases: 1.20's builder() takes no id, since the tab gets its id where it is registered; 1.19.4's
 * builder(id) is FabricItemGroupBuilderImpl, a CreativeModeTab.Builder(null, -1) whose build() also sets the id and adds
 * the tab to the screen. This is that builder without the last two, which BuiltInRegistries' stand-in does when the
 * tab is registered under its id.
 */
public final class FabricItemGroup {

    private FabricItemGroup() {
    }

    public static CreativeModeTab.Builder builder() {
        return new CreativeModeTab.Builder(null, -1);
    }
}
