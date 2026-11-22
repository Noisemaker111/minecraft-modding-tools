package mmt.standin.net.minecraft.network.chat;

import net.minecraft.network.chat.Component;

/**
 * 1.21.4's HoverEvent on 1.21.5.
 *
 * Read from both releases: 1.21.4's new HoverEvent(action, value) paired an action (SHOW_TEXT with a Component,
 * SHOW_ITEM with an ItemStackInfo, SHOW_ENTITY with an EntityTooltipInfo) with its value, getAction() gave the action and
 * getValue(action) the value when the action matched, else null. 1.21.5's HoverEvent is an interface with one record per
 * action (ShowText(Component), ShowItem(ItemStack), ShowEntity(EntityTooltipInfo)) and action() names it.
 *
 * Agrees with NeoForge's 1.21.4 -> 1.21.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.5/index.md, CC BY 4.0): "HoverEvent is now an interface".
 */
public final class HoverEvent {

    private HoverEvent() {}

    public static net.minecraft.network.chat.HoverEvent init(net.minecraft.network.chat.HoverEvent.Action action, Object value) {
        if (action == net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT) {
            return new net.minecraft.network.chat.HoverEvent.ShowText((Component) value);
        }
        if (action == net.minecraft.network.chat.HoverEvent.Action.SHOW_ITEM) {
            return new net.minecraft.network.chat.HoverEvent.ShowItem(((HoverEvent$ItemStackInfo) value).getItemStack());
        }
        if (action == net.minecraft.network.chat.HoverEvent.Action.SHOW_ENTITY) {
            return new net.minecraft.network.chat.HoverEvent.ShowEntity((net.minecraft.network.chat.HoverEvent.EntityTooltipInfo) value);
        }
        throw new IllegalArgumentException("Unknown hover action " + action);
    }

    public static net.minecraft.network.chat.HoverEvent.Action getAction(net.minecraft.network.chat.HoverEvent self) {
        return self.action();
    }

    public static Object getValue(net.minecraft.network.chat.HoverEvent self, net.minecraft.network.chat.HoverEvent.Action action) {
        if (self.action() != action) {
            return null;
        }
        if (self instanceof net.minecraft.network.chat.HoverEvent.ShowText text) {
            return text.value();
        }
        if (self instanceof net.minecraft.network.chat.HoverEvent.ShowItem item) {
            return new HoverEvent$ItemStackInfo(item.item());
        }
        if (self instanceof net.minecraft.network.chat.HoverEvent.ShowEntity entity) {
            return entity.entity();
        }
        return null;
    }
}
