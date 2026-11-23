package mmt.standin.net.minecraft.world.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.saveddata.maps.MapId;

/**
 * 1.20.4's MapItem on 1.20.5.
 *
 * Read from both releases: 1.20.4's getMapId(stack) gave the int under the stack tag's "map" key, or null when the stack
 * had none. 1.20.5 keeps a map's number as the stack's MAP_ID component (MapId, a record of the int), which its data
 * fixer fills from an old stack's "map" tag and which 1.20.5's MapItem reads where 1.20.4 read the tag.
 *
 * Agrees with NeoForge's 1.20.4 -> 1.20.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.20.5/index.md, CC BY 4.0): its data components section (DataComponents.MAP_ID holds a filled map's id).
 */
public final class MapItem {

    private MapItem() {}

    public static Integer getMapId(net.minecraft.world.item.ItemStack stack) {
        MapId id = (MapId) stack.get(DataComponents.MAP_ID);
        return id == null ? null : id.id();
    }
}
