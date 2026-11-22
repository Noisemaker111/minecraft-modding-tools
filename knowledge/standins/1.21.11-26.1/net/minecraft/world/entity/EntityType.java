package mmt.standin.net.minecraft.world.entity;

import net.minecraft.tags.TagKey;

/**
 * 1.21.11's EntityType on 26.1.
 *
 * Read from both releases: 1.21.11's EntityType.is(TagKey) asked the type's registry holder,
 * builtInRegistryHolder.is(tag). 26.1 moves the question to the entity (Entity implements TypedInstance, whose is(TagKey)
 * asks typeHolder()) and keeps the type's holder (builtInRegistryHolder()). So the old method asks that holder, as 1.21.11
 * did.
 *
 * Agrees with NeoForge's 1.21.11 -> 26.1 primer (https://github.com/neoforged/.github/blob/main/primers/26.1/index.md, CC BY 4.0): "EntityType#is -> TypedInstance#is", "Now on the Entity instance".
 */
public final class EntityType {

    private EntityType() {}

    public static boolean is(net.minecraft.world.entity.EntityType self, TagKey tag) {
        return self.builtInRegistryHolder().is(tag);
    }
}
