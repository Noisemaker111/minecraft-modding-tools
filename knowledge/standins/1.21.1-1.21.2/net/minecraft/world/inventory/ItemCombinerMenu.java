package mmt.standin.net.minecraft.world.inventory;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.inventory.Slot;

/**
 * 1.21.1's ItemCombinerMenu on 1.21.2.
 *
 * Read from both releases: 1.21.1 kept inputSlotIndexes, its slot definition's input slots' indexes (each
 * SlotDefinition.slotIndex(), the index in inputSlots). 1.21.2 takes the definition in its constructor and keeps only
 * inputSlots and resultSlotIndex; createInputSlots still adds one Slot over inputSlots at each definition's slotIndex, in
 * order. So the field is the container index of each of the menu's slots that is over inputSlots, in slot order.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "ItemCombinerMenu now takes in an ItemCombinerMenuSlotDefinition" and "getInputSlotIndexes is removed".
 */
public final class ItemCombinerMenu {

    private ItemCombinerMenu() {}

    public static List<Integer> inputSlotIndexes(net.minecraft.world.inventory.ItemCombinerMenu self) {
        List<Integer> indexes = new ArrayList<>();
        for (Object o : self.slots) {
            Slot slot = (Slot) o;
            if (slot.container == self.inputSlots) {
                indexes.add(slot.getContainerSlot());
            }
        }
        return indexes;
    }
}
