package mmt.standin.net.minecraft.world.entity.player;

import java.util.AbstractList;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 1.21.4's Inventory armor, offhand, compartments, getArmor and new Inventory(player) on 1.21.5.
 *
 * Read from both releases: 1.21.4's inventory held three lists, items (36), armor (4: feet, legs, chest, head) and
 * offhand (1), compartments being the three in that order; container slots 36 to 39 were armor 0 to 3 and slot 40 the
 * offhand, and getArmor(i) was armor.get(i). new Inventory(player) made one with its own empty lists. 1.21.5 keeps the
 * 36 items (getNonEquipmentItems()) and reads and writes the worn stacks in the player's EntityEquipment through the same
 * slot numbers (EQUIPMENT_SLOT_MAPPING: FEET.getIndex(36) = 36 up to HEAD = 39, OFFHAND = 40) with getItem and setItem;
 * its new Inventory(player, equipment) takes the equipment it reads, and a new EntityEquipment() is an empty one. So
 * armor and offhand are lists over those slots, read and written through the inventory as 1.21.4's lists were, and a
 * new inventory gets equipment of its own as it had lists of its own.
 *
 * Agrees with NeoForge's 1.21.4 -> 1.21.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.5/index.md, CC BY 4.0):
 * "Inventory: armor, offhand -> EQUIPMENT_SLOT_MAPPING, not one-to-one", "getArmor is removed", "getNonEquipmentItems -
 * Returns the list of non-equipment items in the inventory".
 */
public final class Inventory {

    private Inventory() {}

    /** Slots first .. first + size - 1 of the inventory, as a NonNullList that reads and writes them. */
    private static NonNullList slots(net.minecraft.world.entity.player.Inventory self, int first, int size) {
        List<ItemStack> view = new AbstractList<>() {
            @Override
            public ItemStack get(int index) {
                return self.getItem(first + check(index));
            }

            @Override
            public ItemStack set(int index, ItemStack stack) {
                ItemStack was = self.getItem(first + check(index));
                self.setItem(first + index, stack);
                return was;
            }

            @Override
            public int size() {
                return size;
            }

            private int check(int index) {
                if (index < 0 || index >= size) {
                    throw new IndexOutOfBoundsException(index);
                }
                return index;
            }
        };
        return new NonNullList(view, ItemStack.EMPTY) {
        };
    }

    public static NonNullList armor(net.minecraft.world.entity.player.Inventory self) {
        return slots(self, 36, 4);
    }

    public static NonNullList offhand(net.minecraft.world.entity.player.Inventory self) {
        return slots(self, 40, 1);
    }

    public static List compartments(net.minecraft.world.entity.player.Inventory self) {
        return List.of(self.getNonEquipmentItems(), slots(self, 36, 4), slots(self, 40, 1));
    }

    public static ItemStack getArmor(net.minecraft.world.entity.player.Inventory self, int index) {
        return (ItemStack) slots(self, 36, 4).get(index);
    }

    public static net.minecraft.world.entity.player.Inventory init(Player player) {
        return new net.minecraft.world.entity.player.Inventory(player, new EntityEquipment());
    }
}
