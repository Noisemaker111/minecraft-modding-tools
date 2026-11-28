package mmt.standin.net.minecraft.world.entity.ai.attributes;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 1.21's AttributeModifier(ResourceLocation, double, Operation) on 1.20.6, for a port back: a modifier named by its id.
 *
 * Read from both releases: 1.21's modifier is the record (id, amount, operation), and an attribute holds one modifier
 * per id, so making the same id again replaces it. 1.20.6's is (UUID id, String name, amount, operation), one per UUID;
 * its AttributeModifier(String, double, Operation) draws a random UUID, which would add a second modifier where 1.21
 * replaces one. So the UUID is the one named by the id (UUID.nameUUIDFromBytes of its text), the same every time, and
 * the id is the name.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`AttributeModifier`s no longer take in a `String` representing its UUID. Instead, a `ResourceLocation` is provided to uniquely identity the modifier to apply."
 */
public final class AttributeModifier {

    private AttributeModifier() {}

    public static net.minecraft.world.entity.ai.attributes.AttributeModifier init(net.minecraft.resources.ResourceLocation id, double amount, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation operation) {
        String name = id.toString();
        return new net.minecraft.world.entity.ai.attributes.AttributeModifier(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name, amount, operation);
    }
}
