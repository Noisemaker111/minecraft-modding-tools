package mmt.standin.net.minecraft.world.item.component;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

/**
 * 1.21.10's ItemAttributeModifiers on 1.21.11.
 *
 * Read from both releases: 1.21.10's compute(base, slot) started from base and, for every entry whose slot group holds
 * slot, added its amount (ADD_VALUE), its amount times base (ADD_MULTIPLIED_BASE) or its amount times the value so far
 * (ADD_MULTIPLIED_TOTAL), whatever attribute the entry was for. 1.21.11's compute(attribute, base, slot) does the same
 * over the entries of one attribute only, so the old sum over all of them is written out over modifiers(), which
 * 1.21.11 still gives in the same order.
 *
 * Agrees with NeoForge's 1.21.10 -> 1.21.11 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.11/index.md, CC BY 4.0): "ItemAttributeModifiers#compute now takes in the Attribute holder".
 */
public final class ItemAttributeModifiers {

    private ItemAttributeModifiers() {}

    public static double compute(net.minecraft.world.item.component.ItemAttributeModifiers self, double base, EquipmentSlot slot) {
        double value = base;
        for (Object each : self.modifiers()) {
            net.minecraft.world.item.component.ItemAttributeModifiers.Entry entry = (net.minecraft.world.item.component.ItemAttributeModifiers.Entry) each;
            if (!entry.slot().test(slot)) {
                continue;
            }
            double amount = entry.modifier().amount();
            AttributeModifier.Operation operation = entry.modifier().operation();
            if (operation == AttributeModifier.Operation.ADD_VALUE) {
                value += amount;
            } else if (operation == AttributeModifier.Operation.ADD_MULTIPLIED_BASE) {
                value += amount * base;
            } else {
                value += amount * value;
            }
        }
        return value;
    }
}
