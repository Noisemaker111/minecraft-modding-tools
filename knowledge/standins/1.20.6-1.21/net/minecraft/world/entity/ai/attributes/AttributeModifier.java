package mmt.standin.net.minecraft.world.entity.ai.attributes;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * 1.20.6's AttributeModifier on 1.21.
 *
 * Read from both releases: 1.20.6's modifier was a record of a UUID id, a name (for debugging only: nothing compared
 * or stored by it), an amount and an operation; the constructor without a UUID made a random one
 * (Mth.createInsecureUUID). 1.21's is a record of a ResourceLocation id, an amount and an operation. 1.21's own data
 * fixer (AttributeModifierIdFix) moves a saved old modifier to the new id: vanilla's known UUIDs and names to their new
 * ids, any other UUID to "minecraft:" + the UUID in lower case. A modifier made from a UUID here takes that same id, so
 * it and one the fixer brought over from a world the mod saved are the same modifier. id() gives the UUID back from such
 * an id, and for any other id (vanilla's, or a 1.21 mod's) a UUID made from the id's text, the same for the same id.
 * name() gives the id's text.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0): "AttributeModifiers no longer take in a String representing its UUID. Instead, a ResourceLocation is provided to uniquely identity the modifier to apply."
 */
public final class AttributeModifier {

    private AttributeModifier() {}

    public static net.minecraft.world.entity.ai.attributes.AttributeModifier init(UUID id, String name, double amount, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation operation) {
        return new net.minecraft.world.entity.ai.attributes.AttributeModifier(location(id), amount, operation);
    }

    public static net.minecraft.world.entity.ai.attributes.AttributeModifier init(String name, double amount, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation operation) {
        return init(Mth.createInsecureUUID(RandomSource.createNewThreadLocalInstance()), name, amount, operation);
    }

    public static UUID id(net.minecraft.world.entity.ai.attributes.AttributeModifier self) {
        ResourceLocation id = self.id();
        if (id.getNamespace().equals(ResourceLocation.DEFAULT_NAMESPACE)) {
            try {
                return UUID.fromString(id.getPath());
            } catch (IllegalArgumentException notUuid) {
                // a vanilla or 1.21 id: below
            }
        }
        return UUID.nameUUIDFromBytes(id.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static String name(net.minecraft.world.entity.ai.attributes.AttributeModifier self) {
        return self.id().toString();
    }

    /** The id 1.21's data fixer gives a saved modifier with this UUID that is not one of vanilla's. */
    static ResourceLocation location(UUID id) {
        return ResourceLocation.withDefaultNamespace(id.toString().toLowerCase(Locale.ROOT));
    }
}
