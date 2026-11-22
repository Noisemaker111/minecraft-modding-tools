package mmt.standin.net.minecraft.world.entity;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * 1.21.4's Mob on 1.21.5.
 *
 * Read from both releases: 1.21.4's Mob kept its armour in armorItems (feet, legs, chest, head), returned by
 * getArmorSlots(), and its body armour in the field bodyArmorItem. 1.21.5 keeps both in the entity's EntityEquipment,
 * read with getItemBySlot (EquipmentSlot.FEET to HEAD, and BODY).
 *
 * Agrees with NeoForge's 1.21.4 -> 1.21.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.5/index.md, CC BY 4.0): "getArmorSlots, getHandSlots, getArmorAndBodyArmorSlots, getAllSlots are removed", equipment now in EntityEquipment.
 */
public final class Mob {

    private Mob() {}

    public static Iterable getArmorSlots(net.minecraft.world.entity.Mob self) {
        return mmt.standin.net.minecraft.world.entity.LivingEntity.getArmorSlots(self);
    }

    public static ItemStack bodyArmorItem(net.minecraft.world.entity.Mob self) {
        return self.getItemBySlot(EquipmentSlot.BODY);
    }
}
