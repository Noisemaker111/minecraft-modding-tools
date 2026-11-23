package mmt.standin.net.minecraft.world.item;

import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * 1.20.4's DiggerItem.getAttackDamage() on 1.20.5.
 *
 * Read from both releases: 1.20.4's DiggerItem kept its attack damage in a field (attackDamageBaseline = attackDamage + tier.getAttackDamageBonus()), set in its constructor,
 * and put the same amount in its default attribute modifiers as the ATTACK_DAMAGE modifier with id
 * BASE_ATTACK_DAMAGE_UUID; getAttackDamage() returned the field. 1.20.5 moved attributes into item components: the item's
 * default ItemAttributeModifiers (DiggerItem.createAttributes(tier, ...), read with getDefaultAttributeModifiers()) hold the
 * same ATTACK_DAMAGE modifier, id Item.BASE_ATTACK_DAMAGE_UUID, amount the same sum, and the field is gone. So the old
 * getter reads that modifier's amount (0 when the item has none, as an item made without one now is).
 *
 * Agrees with NeoForge's 1.20.4 -> 1.20.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.20.5/index.md, CC BY 4.0): "DiggerItem#createAttributes - Creates the attribute modifiers given for a tier, attack damage, and attack speed", "Item$Properties#attributes - Sets the attribute modifiers for the item".
 */
public final class DiggerItem {

    private DiggerItem() {}

    public static float getAttackDamage(net.minecraft.world.item.DiggerItem self) {
        for (Object o : self.getDefaultAttributeModifiers().modifiers()) {
            ItemAttributeModifiers.Entry entry = (ItemAttributeModifiers.Entry) o;
            AttributeModifier modifier = entry.modifier();
            if (entry.attribute().equals(Attributes.ATTACK_DAMAGE) && Item.BASE_ATTACK_DAMAGE_UUID.equals(modifier.id())) {
                return (float) modifier.amount();
            }
        }
        return 0.0F;
    }
}
