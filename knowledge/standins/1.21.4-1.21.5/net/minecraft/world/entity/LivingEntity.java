package mmt.standin.net.minecraft.world.entity;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * 1.21.4's LivingEntity on 1.21.5.
 *
 * Read from both releases: 1.21.4's getHandSlots() gave the main and off hand (Mob, Player and ArmorStand each kept a
 * hand list), getArmorSlots() the four armour stacks feet, legs, chest, head (Mob.armorItems, Player's inventory.armor),
 * getArmorAndBodyArmorSlots() those plus a Mob's body armour when it had one, and getAllSlots() hands then armour and body.
 * 1.21.5 keeps every slot in the entity's EntityEquipment and drops the lists; getItemBySlot(slot) gives each stack, so
 * these are the same stacks in the same order.
 *
 * Agrees with NeoForge's 1.21.4 -> 1.21.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.5/index.md, CC BY 4.0): "getArmorSlots, getHandSlots, getArmorAndBodyArmorSlots, getAllSlots are removed", equipment now held in EntityEquipment.
 */
public final class LivingEntity {

    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};

    private LivingEntity() {}

    public static Iterable getHandSlots(net.minecraft.world.entity.LivingEntity self) {
        return List.of(self.getItemBySlot(EquipmentSlot.MAINHAND), self.getItemBySlot(EquipmentSlot.OFFHAND));
    }

    public static Iterable getArmorSlots(net.minecraft.world.entity.LivingEntity self) {
        List<ItemStack> stacks = new ArrayList<>(4);
        for (EquipmentSlot slot : ARMOR) {
            stacks.add(self.getItemBySlot(slot));
        }
        return stacks;
    }

    public static Iterable getArmorAndBodyArmorSlots(net.minecraft.world.entity.LivingEntity self) {
        List<ItemStack> stacks = new ArrayList<>(5);
        for (EquipmentSlot slot : ARMOR) {
            stacks.add(self.getItemBySlot(slot));
        }
        ItemStack body = self.getItemBySlot(EquipmentSlot.BODY);
        if (self instanceof net.minecraft.world.entity.Mob && !body.isEmpty()) {
            stacks.add(body);
        }
        return stacks;
    }

    public static Iterable getAllSlots(net.minecraft.world.entity.LivingEntity self) {
        List<ItemStack> stacks = new ArrayList<>(7);
        stacks.add(self.getItemBySlot(EquipmentSlot.MAINHAND));
        stacks.add(self.getItemBySlot(EquipmentSlot.OFFHAND));
        for (Object stack : getArmorAndBodyArmorSlots(self)) {
            stacks.add((ItemStack) stack);
        }
        return stacks;
    }
}
