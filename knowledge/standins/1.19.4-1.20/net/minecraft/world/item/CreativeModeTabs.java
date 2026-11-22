package mmt.standin.net.minecraft.world.item;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;

/**
 * 1.19.4's CreativeModeTabs fields on 1.20.
 *
 * Read from both releases: 1.19.4's CreativeModeTabs held each built-in tab (BUILDING_BLOCKS ... INVENTORY) as a
 * CreativeModeTab. 1.20 registers the tabs in the creative_mode_tab registry and its fields of the same names are the
 * ResourceKeys, made by createKey from the ids below. A field's old value is the tab the registry holds under that id.
 *
 * Agrees with Fabric's 1.20 post (https://fabricmc.net/2023/05/25/120.html): "`ItemGroup`s are now registered in a
 * registry" and "vanilla `ItemGroups` fields are now registry keys".
 */
public final class CreativeModeTabs {

    private CreativeModeTabs() {}

    static CreativeModeTab tab(String id) {
        return (CreativeModeTab) BuiltInRegistries.CREATIVE_MODE_TAB.getOrThrow(ResourceKey.create(Registries.CREATIVE_MODE_TAB, new ResourceLocation(id)));
    }

    public static CreativeModeTab BUILDING_BLOCKS() {
        return tab("building_blocks");
    }

    public static CreativeModeTab COLORED_BLOCKS() {
        return tab("colored_blocks");
    }

    public static CreativeModeTab NATURAL_BLOCKS() {
        return tab("natural_blocks");
    }

    public static CreativeModeTab FUNCTIONAL_BLOCKS() {
        return tab("functional_blocks");
    }

    public static CreativeModeTab REDSTONE_BLOCKS() {
        return tab("redstone_blocks");
    }

    public static CreativeModeTab HOTBAR() {
        return tab("hotbar");
    }

    public static CreativeModeTab SEARCH() {
        return tab("search");
    }

    public static CreativeModeTab TOOLS_AND_UTILITIES() {
        return tab("tools_and_utilities");
    }

    public static CreativeModeTab COMBAT() {
        return tab("combat");
    }

    public static CreativeModeTab FOOD_AND_DRINKS() {
        return tab("food_and_drinks");
    }

    public static CreativeModeTab INGREDIENTS() {
        return tab("ingredients");
    }

    public static CreativeModeTab SPAWN_EGGS() {
        return tab("spawn_eggs");
    }

    public static CreativeModeTab OP_BLOCKS() {
        return tab("op_blocks");
    }

    public static CreativeModeTab INVENTORY() {
        return tab("inventory");
    }
}
