package mmt.standin.net.minecraft.world.item;

import com.mojang.serialization.Dynamic;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
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
 * Agrees with NeoForge's 1.20.4 -> 1.20.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.20.5/index.md, CC BY 4.0): "stack.set(DataComponents.CUSTOM_NAME, ...)", "stack.remove(DataComponents.CUSTOM_NAME)", "getComponentsPatch - Gets the patches to the component map on the current ItemStack".
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
}
