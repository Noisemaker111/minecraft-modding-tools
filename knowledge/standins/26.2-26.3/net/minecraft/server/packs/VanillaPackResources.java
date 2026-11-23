package mmt.standin.net.minecraft.server.packs;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;

/**
 * 26.2's VanillaPackResources.getResource(type, id) on 26.3.
 *
 * Read from both releases: 26.2's VanillaPackResources was the built-in pack (a PackResources): getResource(type, id)
 * looked the path up in its root paths for that pack type and gave an IoSupplier of the file, or null. 26.3 splits it:
 * VanillaPackResources no longer is a PackResources and holds fullResources(), a FixedPathPackResources with the same
 * root and per-type paths, whose getResource(type, id) does that lookup, beside the overlay layers. So the old method asks
 * fullResources().
 *
 * Agrees with NeoForge's 26.2 -> 26.3 primer (https://github.com/neoforged/.github/blob/main/primers/26.3/index.md, CC BY 4.0): "VanillaPackResources no longer implements PackResources", "FixedPathPackResources - A pack that has all resources defined on class initialization", "fullResources - Returns all resourcee provided".
 */
public final class VanillaPackResources {

    private VanillaPackResources() {}

    public static IoSupplier getResource(net.minecraft.server.packs.VanillaPackResources self, PackType type, Identifier id) {
        return self.fullResources().getResource(type, id);
    }
}
