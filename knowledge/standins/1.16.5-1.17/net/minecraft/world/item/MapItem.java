package mmt.standin.net.minecraft.world.item;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * 1.16.5's MapItem.getOrCreateSavedData(ItemStack, Level) and getMapId(ItemStack) on 1.17.
 *
 * Read from both releases: 1.16.5's getOrCreateSavedData returned the map's data, and on a server level made it when the
 * stack had none: a new map id, data centred on the world spawn at scale 3, not tracking, stored under the id on the
 * stack. getMapId returned the stack's "map" number, 0 when it had none. 1.17 makes a map's data only when the map item
 * is made (MapItem.create), getSavedData returns null without it, and getMapId returns null for no id. This makes a map
 * item as 1.16.5 made the data and puts its id on the stack, then reads the data as 1.17 does; getMapId answers 0 for
 * none as before.
 *
 * Fabric's 1.17 post for developers (https://fabricmc.net/2021/05/27/117-for-developers.html) covers Java 16, Fabric API and
 * Yarn, not this; this is from the game's code alone.
 */
public final class MapItem {

    private MapItem() {}

    public static MapItemSavedData getOrCreateSavedData(ItemStack stack, Level level) {
        MapItemSavedData data = net.minecraft.world.item.MapItem.getSavedData(stack, level);
        if (data == null && level instanceof ServerLevel) {
            ItemStack made = net.minecraft.world.item.MapItem.create(level, level.getLevelData().getXSpawn(), level.getLevelData().getZSpawn(), (byte) 3, false, false);
            stack.getOrCreateTag().putInt("map", net.minecraft.world.item.MapItem.getMapId(made));
            data = net.minecraft.world.item.MapItem.getSavedData(stack, level);
        }
        return data;
    }

    public static int getMapId(ItemStack stack) {
        Integer id = net.minecraft.world.item.MapItem.getMapId(stack);
        return id == null ? 0 : id;
    }
}
