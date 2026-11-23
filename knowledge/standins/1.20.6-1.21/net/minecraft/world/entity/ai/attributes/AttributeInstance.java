package mmt.standin.net.minecraft.world.entity.ai.attributes;

import java.util.UUID;

/**
 * 1.20.6's AttributeInstance on 1.21.
 *
 * Read from both releases: 1.20.6 kept an instance's modifiers by UUID: getModifier(uuid), removeModifier(uuid) (from
 * every map), removePermanentModifier(uuid) (only a permanent one, telling whether there was one) and
 * hasModifier(modifier) (whether one with its UUID was there). 1.21 keeps them by ResourceLocation, with
 * getModifier(id), removeModifier(id), which removes it from every map including the permanent ones, and
 * hasModifier(id). A UUID's id is the one the AttributeModifier stand-in and 1.21's data fixer give it. 1.21 has no way
 * to remove only a permanent modifier, so removePermanentModifier removes the modifier with that id, which is the
 * permanent one wherever a mod used one UUID for one modifier.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0): "AttributeModifiers no longer take in a String representing its UUID. Instead, a ResourceLocation is provided to uniquely identity the modifier to apply."
 */
public final class AttributeInstance {

    private AttributeInstance() {}

    public static net.minecraft.world.entity.ai.attributes.AttributeModifier getModifier(net.minecraft.world.entity.ai.attributes.AttributeInstance self, UUID id) {
        return self.getModifier(AttributeModifier.location(id));
    }

    public static void removeModifier(net.minecraft.world.entity.ai.attributes.AttributeInstance self, UUID id) {
        self.removeModifier(AttributeModifier.location(id));
    }

    public static boolean removePermanentModifier(net.minecraft.world.entity.ai.attributes.AttributeInstance self, UUID id) {
        return self.removeModifier(AttributeModifier.location(id));
    }

    public static boolean hasModifier(net.minecraft.world.entity.ai.attributes.AttributeInstance self, net.minecraft.world.entity.ai.attributes.AttributeModifier modifier) {
        return self.hasModifier(modifier.id());
    }
}
