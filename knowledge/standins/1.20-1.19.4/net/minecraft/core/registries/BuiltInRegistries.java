package mmt.standin.net.minecraft.core.registries;

import com.mojang.serialization.Lifecycle;
import mmt.standin.net.minecraft.world.item.CreativeModeTabs;
import net.fabricmc.fabric.impl.itemgroup.FabricItemGroup;
import net.fabricmc.fabric.impl.itemgroup.ItemGroupHelper;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;

/**
 * 1.20's BuiltInRegistries.CREATIVE_MODE_TAB on 1.19.4.
 *
 * Read from both releases: 1.20 registers each creative tab (Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id,
 * tab)) and lists the registry's entries on the creative screen; 1.19.4 has no such registry, and Fabric API for 1.19.4
 * adds a tab to the screen when its builder is built (FabricItemGroupBuilderImpl.build: setId, then
 * ItemGroupHelper.appendItemGroup). This is a registry of 1.19.4's own kind (MappedRegistry) that does what that build
 * did when a tab is registered, with the tab's id from its key; 1.20's FabricItemGroup.builder() (no id) builds a tab
 * Fabric has not added yet (see FabricItemGroup). The vanilla tabs are in it under 1.20's keys (CreativeModeTabs), as
 * they are in 1.20's registry, so a key or a lookup by tab finds them.
 */
public final class BuiltInRegistries {

    private static final Tabs CREATIVE_MODE_TAB = new Tabs();

    private BuiltInRegistries() {
    }

    public static Registry CREATIVE_MODE_TAB() {
        return CREATIVE_MODE_TAB;
    }

    static final class Tabs extends MappedRegistry {

        private final boolean ready;

        Tabs() {
            super(Registries.CREATIVE_MODE_TAB(), Lifecycle.stable());
            vanilla(CreativeModeTabs.BUILDING_BLOCKS(), net.minecraft.world.item.CreativeModeTabs.BUILDING_BLOCKS);
            vanilla(CreativeModeTabs.COLORED_BLOCKS(), net.minecraft.world.item.CreativeModeTabs.COLORED_BLOCKS);
            vanilla(CreativeModeTabs.NATURAL_BLOCKS(), net.minecraft.world.item.CreativeModeTabs.NATURAL_BLOCKS);
            vanilla(CreativeModeTabs.FUNCTIONAL_BLOCKS(), net.minecraft.world.item.CreativeModeTabs.FUNCTIONAL_BLOCKS);
            vanilla(CreativeModeTabs.REDSTONE_BLOCKS(), net.minecraft.world.item.CreativeModeTabs.REDSTONE_BLOCKS);
            vanilla(CreativeModeTabs.HOTBAR(), net.minecraft.world.item.CreativeModeTabs.HOTBAR);
            vanilla(CreativeModeTabs.SEARCH(), net.minecraft.world.item.CreativeModeTabs.SEARCH);
            vanilla(CreativeModeTabs.TOOLS_AND_UTILITIES(), net.minecraft.world.item.CreativeModeTabs.TOOLS_AND_UTILITIES);
            vanilla(CreativeModeTabs.COMBAT(), net.minecraft.world.item.CreativeModeTabs.COMBAT);
            vanilla(CreativeModeTabs.FOOD_AND_DRINKS(), net.minecraft.world.item.CreativeModeTabs.FOOD_AND_DRINKS);
            vanilla(CreativeModeTabs.INGREDIENTS(), net.minecraft.world.item.CreativeModeTabs.INGREDIENTS);
            vanilla(CreativeModeTabs.SPAWN_EGGS(), net.minecraft.world.item.CreativeModeTabs.SPAWN_EGGS);
            vanilla(CreativeModeTabs.OP_BLOCKS(), net.minecraft.world.item.CreativeModeTabs.OP_BLOCKS);
            vanilla(CreativeModeTabs.INVENTORY(), net.minecraft.world.item.CreativeModeTabs.INVENTORY);
            ready = true;
        }

        private void vanilla(ResourceKey key, CreativeModeTab tab) {
            super.register(key, tab, Lifecycle.stable());
        }

        @Override
        public Holder.Reference register(ResourceKey key, Object tab, Lifecycle lifecycle) {
            Holder.Reference out = super.register(key, tab, lifecycle);
            if (ready) {
                ((FabricItemGroup) tab).setId(key.location());
                ItemGroupHelper.appendItemGroup((CreativeModeTab) tab);
            }
            return out;
        }
    }
}
