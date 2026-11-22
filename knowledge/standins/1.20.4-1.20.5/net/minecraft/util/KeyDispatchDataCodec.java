package mmt.standin.net.minecraft.util;

import com.mojang.serialization.Codec;

/**
 * 1.20.4's KeyDispatchDataCodec on 1.20.5.
 *
 * Read from both releases: 1.20.4's KeyDispatchDataCodec held a Codec (of(MapCodec) kept the map codec's codec());
 * 1.20.5's holds the MapCodec itself. The old codec() is the map codec's codec().
 *
 * NeoForge's 1.20.4 -> 1.20.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.20.5/index.md, CC BY 4.0) does not list this record; it is from the game's code alone.
 */
public final class KeyDispatchDataCodec {

    private KeyDispatchDataCodec() {}

    public static Codec codec(net.minecraft.util.KeyDispatchDataCodec self) {
        return self.codec().codec();
    }
}
