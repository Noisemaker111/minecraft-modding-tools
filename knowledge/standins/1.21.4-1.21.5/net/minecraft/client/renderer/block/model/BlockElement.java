package mmt.standin.net.minecraft.client.renderer.block.model;

import org.joml.Vector3f;

/**
 * 1.21.4's BlockElement on 1.21.5.
 *
 * Read from both releases: 1.21.4's BlockElement kept its corners in the public final fields from and to; 1.21.5's is a
 * record with the same corners as its components from() and to(), typed as the read-only Vector3fc (the vector the
 * model reader made, a Vector3f).
 *
 * Agrees with NeoForge's 1.21.4 -> 1.21.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.5/index.md, CC BY 4.0): "BlockElement is now a record".
 */
public final class BlockElement {

    private BlockElement() {}

    public static Vector3f from(net.minecraft.client.renderer.block.model.BlockElement self) {
        return self.from() instanceof Vector3f vector ? vector : new Vector3f(self.from());
    }

    public static Vector3f to(net.minecraft.client.renderer.block.model.BlockElement self) {
        return self.to() instanceof Vector3f vector ? vector : new Vector3f(self.to());
    }
}
