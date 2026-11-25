package mmt.standin.net.minecraft.world.item;

import mmt.standin.net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;

/**
 * 1.20's CreativeModeTabs keys on 1.19.4.
 *
 * Read from both releases: 1.20's CreativeModeTabs fields are the keys its vanilla tabs are registered under
 * (createKey("building_blocks"), ... "tools_and_utilities", "food_and_drinks", "op_blocks", "inventory"), where 1.19.4's
 * fields of the same names are the tabs themselves. Each key here is 1.20's, and BuiltInRegistries' stand-in holds
 * 1.19.4's tab of the same field under it (tab), so a key leads where the field did.
 */
public final class CreativeModeTabs {

    private CreativeModeTabs() {
    }

    static ResourceKey key(String name) {
        return ResourceKey.create(Registries.CREATIVE_MODE_TAB(), new ResourceLocation(name));
    }

    public static ResourceKey BUILDING_BLOCKS() {
        return key("building_blocks");
    }

    public static ResourceKey COLORED_BLOCKS() {
        return key("colored_blocks");
    }

    public static ResourceKey NATURAL_BLOCKS() {
        return key("natural_blocks");
    }

    public static ResourceKey FUNCTIONAL_BLOCKS() {
        return key("functional_blocks");
    }

    public static ResourceKey REDSTONE_BLOCKS() {
        return key("redstone_blocks");
    }

    public static ResourceKey HOTBAR() {
        return key("hotbar");
    }

    public static ResourceKey SEARCH() {
        return key("search");
    }

    public static ResourceKey TOOLS_AND_UTILITIES() {
        return key("tools_and_utilities");
    }

    public static ResourceKey COMBAT() {
        return key("combat");
    }

    public static ResourceKey FOOD_AND_DRINKS() {
        return key("food_and_drinks");
    }

    public static ResourceKey INGREDIENTS() {
        return key("ingredients");
    }

    public static ResourceKey SPAWN_EGGS() {
        return key("spawn_eggs");
    }

    public static ResourceKey OP_BLOCKS() {
        return key("op_blocks");
    }

    public static ResourceKey INVENTORY() {
        return key("inventory");
    }
}
