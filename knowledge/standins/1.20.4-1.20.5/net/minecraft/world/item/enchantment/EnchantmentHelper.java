package mmt.standin.net.minecraft.world.item.enchantment;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * 1.20.4's EnchantmentHelper on 1.20.5.
 *
 * Read from both releases: 1.20.4's getEnchantments(stack) read the stack's enchantment list (for an enchanted book its
 * stored enchantments, else its own) into a map of each enchantment to its level, in the list's order. 1.20.5 keeps
 * them as data components, ItemEnchantments under STORED_ENCHANTMENTS for a book and ENCHANTMENTS otherwise, whose
 * entries are each enchantment's holder and level. The same map, read from the components.
 *
 * Agrees with NeoForge's 1.20.4 -> 1.20.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.20.5/index.md, CC BY 4.0): "getEnchantments - Gets the item enchantments from the data component".
 */
public final class EnchantmentHelper {

    private EnchantmentHelper() {}

    public static Map getEnchantments(ItemStack stack) {
        ItemEnchantments enchantments = (ItemEnchantments) stack.getOrDefault(
            stack.is(Items.ENCHANTED_BOOK) ? DataComponents.STORED_ENCHANTMENTS : DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        Map out = new LinkedHashMap();
        for (Object o : enchantments.entrySet()) {
            it.unimi.dsi.fastutil.objects.Object2IntMap.Entry entry = (it.unimi.dsi.fastutil.objects.Object2IntMap.Entry) o;
            out.put(((Holder) entry.getKey()).value(), entry.getIntValue());
        }
        return out;
    }
}
