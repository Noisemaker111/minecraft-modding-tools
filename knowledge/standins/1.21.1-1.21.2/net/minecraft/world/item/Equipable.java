package mmt.standin.net.minecraft.world.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.level.block.Block;

/**
 * 1.21.1's Equipable, which 1.21.2 dropped, on 1.21.2.
 *
 * Read from both releases: 1.21.1's interface was implemented by the items and blocks that go into an equipment slot when
 * used: ArmorItem (and AnimalArmorItem through it), ElytraItem, ShieldItem, and the blocks AbstractSkullBlock,
 * EquipableCarvedPumpkinBlock and WoolCarpetBlock (Equipable.get(stack) also took a BlockItem's block). 1.21.2 says it
 * with the item's EQUIPPABLE component: Items gives it to the armor, the elytra, the carved pumpkin, the heads and skulls
 * and the carpets (llama swag), and not to the shield, which is held in the off hand by its use. So something was an
 * Equipable when it is an item whose components have EQUIPPABLE, a shield, or a block whose item is such an item. A mod's
 * own class that implemented it implements this interface.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "Equippable, which used to be an interface, is now a data component that contains how an entity can equip this item".
 */
public interface Equipable {

    EquipmentSlot getEquipmentSlot();

    static boolean isInstance(Object o) {
        if (o instanceof Equipable || o instanceof ShieldItem) {
            return true;
        }
        if (o instanceof Block block) {
            o = block.asItem();
        }
        return o instanceof Item item && item.components().has(DataComponents.EQUIPPABLE);
    }
}
