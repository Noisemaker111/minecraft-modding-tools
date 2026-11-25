package mmt.standin.net.fabricmc.fabric.api.itemgroup.v1;

import mmt.standin.net.minecraft.core.registries.BuiltInRegistries;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;

/**
 * Fabric API 1.20's ItemGroupEvents.modifyEntriesEvent(ResourceKey) on 1.19.4.
 *
 * Read from both releases: 1.20 names a tab by its registry key; Fabric API for 1.19.4 by its id
 * (modifyEntriesEvent(ResourceLocation)), and modifyEntriesEvent(tab) asks the tab for that id. A vanilla tab's 1.20 key
 * is not its 1.19.4 id (tools_and_utilities is minecraft:tools there), so a key the registry holds asks by the tab; any
 * other (a mod's tab, registered under that key as its id, or not yet registered) asks by the key's id.
 */
public final class ItemGroupEvents {

    private ItemGroupEvents() {
    }

    public static Event modifyEntriesEvent(ResourceKey key) {
        Object tab = BuiltInRegistries.CREATIVE_MODE_TAB().get(key);
        return tab != null ? net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents.modifyEntriesEvent((CreativeModeTab) tab) : net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents.modifyEntriesEvent(key.location());
    }
}
