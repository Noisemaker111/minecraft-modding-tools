package mmt.standin.net.minecraft.world.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.DyeColor;

/**
 * 1.21.11's DyeItem on 26.1.
 *
 * Read from both releases: 1.21.11's DyeItem was made for one color (DyeItem(DyeColor, Properties)) and getDyeColor()
 * returned it. 26.1's DyeItem(Properties) keeps no color; each dye item carries it as its DYE component (Items: white_dye
 * is DyeItem::new with component(DataComponents.DYE, DyeColor.WHITE)), which the game reads to dye sheep and signs. So the
 * old method reads the item's own DYE component, white when it has none.
 *
 * Agrees with NeoForge's 1.21.11 -> 26.1 primer (https://github.com/neoforged/.github/blob/main/primers/26.1/index.md, CC BY 4.0): "DyeItem no longer takes in the DyeColor", "getDyeColor, byColor are removed", and "those without the DYE component will default to DyeColor#WHITE".
 */
public final class DyeItem {

    private DyeItem() {}

    public static DyeColor getDyeColor(net.minecraft.world.item.DyeItem self) {
        return (DyeColor) self.components().getOrDefault(DataComponents.DYE, DyeColor.WHITE);
    }
}
