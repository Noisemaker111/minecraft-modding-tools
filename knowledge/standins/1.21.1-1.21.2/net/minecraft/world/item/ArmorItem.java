package mmt.standin.net.minecraft.world.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;

/**
 * 1.21.1's ArmorItem.getEquipmentSlot(), getDefense() and getToughness() on 1.21.2.
 *
 * Read from both releases: 1.21.1's ArmorItem kept its material and type; the slot was the type's slot, the defense the
 * material's defense for the type, the toughness the material's, and its default attribute modifiers added exactly those
 * as ARMOR and ARMOR_TOUGHNESS for the slot. 1.21.2's ArmorItem keeps nothing: ArmorMaterial.humanoidProperties puts the
 * slot in the item's EQUIPPABLE component (Equippable.builder(type.getSlot())) and the defense and toughness in its
 * ATTRIBUTE_MODIFIERS, each an ADD_VALUE modifier named armor.TYPE (createAttributes). So these read the item's own
 * components; an item with no such modifier has none of it (0), as a material with no defense for the type gave.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "ArmorItem is no longer equipable ... its only remaining usage is to prevent armor change when enchanted and get the associated attributes", and ArmorMaterial "Bascially a dummy record to easily handle applying the associated data components (MAX_DAMAGE, ATTRIBUTE_MODIFIERS, ENCHANTABLE, EQUIPPABLE, REPAIRABLE)".
 */
public final class ArmorItem {

    private ArmorItem() {}

    public static EquipmentSlot getEquipmentSlot(net.minecraft.world.item.ArmorItem self) {
        Equippable equippable = (Equippable) self.components().get(DataComponents.EQUIPPABLE);
        return equippable == null ? EquipmentSlot.MAINHAND : equippable.slot();
    }

    public static int getDefense(net.minecraft.world.item.ArmorItem self) {
        return (int) armorValue(self, Attributes.ARMOR);
    }

    public static float getToughness(net.minecraft.world.item.ArmorItem self) {
        return (float) armorValue(self, Attributes.ARMOR_TOUGHNESS);
    }

    private static double armorValue(net.minecraft.world.item.ArmorItem item, Object attribute) {
        ItemAttributeModifiers modifiers = (ItemAttributeModifiers) item.components().get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (modifiers == null) {
            return 0;
        }
        double value = 0;
        for (Object o : modifiers.modifiers()) {
            ItemAttributeModifiers.Entry entry = (ItemAttributeModifiers.Entry) o;
            AttributeModifier modifier = entry.modifier();
            if (entry.attribute().equals(attribute) && modifier.operation() == AttributeModifier.Operation.ADD_VALUE && modifier.id().getPath().startsWith("armor.")) {
                value += modifier.amount();
            }
        }
        return value;
    }
}
