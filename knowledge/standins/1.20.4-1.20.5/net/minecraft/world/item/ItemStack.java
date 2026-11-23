package mmt.standin.net.minecraft.world.item;

import com.mojang.serialization.Dynamic;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;

/**
 * 1.20.4's ItemStack on 1.20.5.
 *
 * Read from both releases: 1.20.4's ItemStack kept everything beyond its item and count in one NBT tag: hasTag() was a
 * non-empty stack with a non-empty tag, and setHoverName(name) wrote the name into the tag's display.Name (a null name
 * removed it) and returned the stack. 1.20.5 keeps that data as data components, the ones that differ from the
 * item's defaults being the stack's patch (getComponentsPatch()); the custom name is the CUSTOM_NAME component. A tag
 * with something in it is a non-empty patch; the name is set or removed on the component.
 * 1.20.4's ItemStack.of(nbt) read a stack saved as {id, Count, tag}; setTag(tag) replaced the stack's whole tag (null
 * for none). 1.20.5 saves a stack as {id, count, components} and converts the old form with its own data fixer
 * (DataFixers.getDataFixer(), the ITEM_STACK type, from 1.20.4's data version 3700), as it does for an old world's
 * items. So an old stack is the fixer's conversion read with ItemStack.parseOptional (the built-in registries, which
 * every 1.20.4 item lived in), and a tag is set by resetting the stack's components to its item's defaults and
 * applying those the fixer makes of the stack with that tag.
 *
 * The rest of the old tag: 1.20.4's getTag() gave the stack's tag (null for none) and getOrCreateTag() made an empty
 * one first; getTagElement(key) gave a compound under key or null, getOrCreateTagElement(key) put an empty one there
 * first, addTagElement(key, value) put value in the stack's tag, and removeTagKey(key) removed key, dropping the tag
 * when it was left empty. 1.20.5 keeps whatever of a stack's old tag no component took (a mod's own keys) in the
 * CUSTOM_DATA component, which its own data fixer fills from an old tag, and CustomData.update drops the component when
 * its tag is left empty as removeTagKey dropped the tag. The tag these give is the component's own, in a CustomData
 * made for this stack alone (component values are shared between copies of a stack), so a mod writing into it writes
 * the stack's data as it did. Keys 1.20.5 moved into their own components (Damage, Enchantments, display) are not in
 * it; a stack whose patch holds only those gives an empty tag, as hasTag() says it has one.
 * 1.20.4's hurtAndBreak(amount, entity, onBroken) did nothing on the client or for a player in creative, and otherwise
 * damaged the stack (hurt, with Unbreaking, the player's durability trigger) and on breaking ran onBroken, shrank the
 * stack, gave a player the item-broken stat and reset its damage. 1.20.5's hurtAndBreak(amount, random, player,
 * onBreak) is that damage step, running onBreak when the stack breaks. isEdible() asked the item for food properties,
 * which 1.20.5 keeps as the stack's FOOD component. getTooltipLines(player, flag) is 1.20.5's with the context its own
 * screens pass (Item.TooltipContext.of the player's level, EMPTY without a player).
 *
 * Agrees with NeoForge's 1.20.4 -> 1.20.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.20.5/index.md, CC BY 4.0): "stack.set(DataComponents.CUSTOM_NAME, ...)", "stack.remove(DataComponents.CUSTOM_NAME)", "getComponentsPatch - Gets the patches to the component map on the current ItemStack", and its data components section: arbitrary NBT a mod kept on a stack goes in the CUSTOM_DATA component.
 */
public final class ItemStack {

    private ItemStack() {}

    public static boolean hasTag(net.minecraft.world.item.ItemStack self) {
        return !self.isEmpty() && !self.getComponentsPatch().isEmpty();
    }

    public static net.minecraft.world.item.ItemStack setHoverName(net.minecraft.world.item.ItemStack self, Component name) {
        if (name != null) {
            self.set(DataComponents.CUSTOM_NAME, name);
        } else {
            self.remove(DataComponents.CUSTOM_NAME);
        }
        return self;
    }

    public static net.minecraft.world.item.ItemStack of(CompoundTag nbt) {
        int now = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        Dynamic fixed = DataFixers.getDataFixer().update(References.ITEM_STACK, new Dynamic(NbtOps.INSTANCE, nbt), 3700, now);
        return net.minecraft.world.item.ItemStack.parseOptional(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY), (CompoundTag) fixed.getValue());
    }

    public static void setTag(net.minecraft.world.item.ItemStack self, CompoundTag tag) {
        for (Object o : self.getComponentsPatch().entrySet()) {
            DataComponentType type = (DataComponentType) ((Map.Entry) o).getKey();
            self.set(type, self.getPrototype().get(type));
        }
        if (tag == null || self.isEmpty()) {
            return;
        }
        CompoundTag old = new CompoundTag();
        old.putString("id", BuiltInRegistries.ITEM.getKey(self.getItem()).toString());
        old.putByte("Count", (byte) 1);
        old.put("tag", tag.copy());
        self.applyComponents(of(old).getComponentsPatch());
    }

    public static CompoundTag getTag(net.minecraft.world.item.ItemStack self) {
        CustomData data = (CustomData) self.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return hasTag(self) ? new CompoundTag() : null;
        }
        return own(self, data);
    }

    public static CompoundTag getOrCreateTag(net.minecraft.world.item.ItemStack self) {
        CustomData data = (CustomData) self.get(DataComponents.CUSTOM_DATA);
        return own(self, data == null ? CustomData.EMPTY : data);
    }

    public static CompoundTag getTagElement(net.minecraft.world.item.ItemStack self, String key) {
        CustomData data = (CustomData) self.get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.getUnsafe().contains(key, Tag.TAG_COMPOUND)) {
            return null;
        }
        return own(self, data).getCompound(key);
    }

    public static CompoundTag getOrCreateTagElement(net.minecraft.world.item.ItemStack self, String key) {
        CompoundTag tag = getOrCreateTag(self);
        if (tag.contains(key, Tag.TAG_COMPOUND)) {
            return tag.getCompound(key);
        }
        CompoundTag element = new CompoundTag();
        tag.put(key, element);
        return element;
    }

    public static void addTagElement(net.minecraft.world.item.ItemStack self, String key, Tag value) {
        getOrCreateTag(self).put(key, value);
    }

    public static void removeTagKey(net.minecraft.world.item.ItemStack self, String key) {
        CustomData data = (CustomData) self.get(DataComponents.CUSTOM_DATA);
        if (data != null && data.contains(key)) {
            CustomData.update(DataComponents.CUSTOM_DATA, self, (Consumer<CompoundTag>) tag -> tag.remove(key));
        }
    }

    public static void hurtAndBreak(net.minecraft.world.item.ItemStack self, int amount, LivingEntity entity, Consumer onBroken) {
        if (entity.level().isClientSide || entity instanceof Player player && player.getAbilities().instabuild) {
            return;
        }
        self.hurtAndBreak(amount, entity.getRandom(), entity instanceof ServerPlayer serverPlayer ? serverPlayer : null, () -> {
            onBroken.accept(entity);
            Item item = self.getItem();
            self.shrink(1);
            if (entity instanceof Player player) {
                player.awardStat(Stats.ITEM_BROKEN.get(item));
            }
            self.setDamageValue(0);
        });
    }

    public static boolean isEdible(net.minecraft.world.item.ItemStack self) {
        return self.has(DataComponents.FOOD);
    }

    public static List getTooltipLines(net.minecraft.world.item.ItemStack self, Player player, TooltipFlag flag) {
        return self.getTooltipLines(player == null ? Item.TooltipContext.EMPTY : Item.TooltipContext.of(player.level()), player, flag);
    }

    /** The stack's custom data in a CustomData of its own (copied, as CustomData.of copies), whose tag it gives. */
    private static CompoundTag own(net.minecraft.world.item.ItemStack self, CustomData data) {
        CustomData mine = CustomData.of(data.getUnsafe());
        self.set(DataComponents.CUSTOM_DATA, mine);
        return mine.getUnsafe();
    }
}
