package mmt.standin.net.minecraft.advancements;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.item.ItemStack;

/**
 * 1.21.11's DisplayInfo on 26.1.
 *
 * Read from both releases: 1.21.11's DisplayInfo kept its icon as an ItemStack and getIcon() returned that one stack.
 * 26.1 keeps an ItemStackTemplate (immutable) and getIcon() returns it; the game's own advancement screen makes the
 * stack it draws once, from the template (AdvancementWidget: this.icon = display.getIcon().create()). So the old getIcon
 * is the template's stack, made once per display and kept, as 1.21.11 kept one.
 *
 * Agrees with NeoForge's 1.21.11 -> 26.1 primer (https://github.com/neoforged/.github/blob/main/primers/26.1/index.md, CC BY 4.0): "DisplayInfo now takes in an ItemStackTemplate instead of an ItemStack", "getIcon now returns an ItemStackTemplate instead of an ItemStack", and "The template can be transformed to a stack via create".
 */
public final class DisplayInfo {

    private static final Map<net.minecraft.advancements.DisplayInfo, ItemStack> ICONS = Collections.synchronizedMap(new WeakHashMap<>());

    private DisplayInfo() {}

    public static ItemStack getIcon(net.minecraft.advancements.DisplayInfo self) {
        return ICONS.computeIfAbsent(self, display -> display.getIcon().create());
    }
}
