package mmt.standin.net.minecraft.world.entity.decoration;

/**
 * 1.21's PaintingVariant.assetId() on 1.20.6, for a port back: the variant's registry id.
 *
 * Read from both releases: 1.21's PaintingVariant is a data record (width, height, assetId), and each vanilla variant's
 * asset id is its own id (painting/ID in the paintings atlas). 1.20.6's PaintingVariant is a registered class
 * (width, height) whose texture PaintingTextureManager finds by its key in BuiltInRegistries.PAINTING_VARIANT.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`PaintingVariant`s are now a datapack registry object."
 */
public final class PaintingVariant {

    private PaintingVariant() {}

    public static net.minecraft.resources.ResourceLocation assetId(net.minecraft.world.entity.decoration.PaintingVariant self) {
        return net.minecraft.core.registries.BuiltInRegistries.PAINTING_VARIANT.getKey(self);
    }
}
