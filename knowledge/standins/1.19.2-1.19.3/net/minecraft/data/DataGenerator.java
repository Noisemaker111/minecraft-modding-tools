package mmt.standin.net.minecraft.data;

import java.nio.file.Path;

/**
 * 1.19.2's DataGenerator on 1.19.3.
 *
 * Read from both releases: 1.19.2's getOutputFolder() gave the root folder the generator was made with, under which each
 * pack type's folder is (getOutputFolder(target)). 1.19.3 keeps that root as rootOutputFolder (private) and hands
 * providers a PackOutput for the vanilla pack over it instead of the generator; reading the field is the old getter (the
 * port opens it through the mod's access transformer or widener).
 *
 * Agrees with NeoForge's 1.19.2 -> 1.19.3 primer (https://github.com/neoforged/.github/blob/main/primers/1.19.3/index.md, CC BY 4.0): "all providers now take in a PackOutput compared to the DataGenerator itself. The PackOutput simply specifies the directory where the pack will be generated."
 */
public final class DataGenerator {

    private DataGenerator() {}

    public static Path getOutputFolder(net.minecraft.data.DataGenerator self) {
        return self.rootOutputFolder;
    }
}
