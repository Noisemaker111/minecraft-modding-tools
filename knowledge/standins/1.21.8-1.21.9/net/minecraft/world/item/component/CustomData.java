package mmt.standin.net.minecraft.world.item.component;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapDecoder;
import com.mojang.serialization.MapLike;
import net.minecraft.nbt.NbtOps;

/**
 * 1.21.8's CustomData on 1.21.9.
 *
 * Read from both releases: 1.21.8's CustomData wrapped one CompoundTag and read it in place: contains(key) and size() asked
 * the tag; read(ops, decoder) decoded the tag as a map with the decoder, and read(decoder) did so with NbtOps. 1.21.9 keeps
 * the same wrapped tag but offers only isEmpty(), copyTag() and the update/set helpers. Each read here asks a copy of the
 * tag (copyTag()), which holds the same entries, so the answers are the same; nothing is written through it.
 *
 * NeoForge's 1.21.8 -> 1.21.9 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.9/index.md, CC BY 4.0) covers CustomData's entity and block entity methods moving to TypedEntityData; contains, read and size are from the game's code alone.
 */
public final class CustomData {

    private CustomData() {}

    public static boolean contains(net.minecraft.world.item.component.CustomData self, String key) {
        return self.copyTag().contains(key);
    }

    public static int size(net.minecraft.world.item.component.CustomData self) {
        return self.copyTag().size();
    }

    public static DataResult read(net.minecraft.world.item.component.CustomData self, MapDecoder decoder) {
        return read(self, NbtOps.INSTANCE, decoder);
    }

    public static DataResult read(net.minecraft.world.item.component.CustomData self, DynamicOps ops, MapDecoder decoder) {
        MapLike map = (MapLike) ops.getMap(self.copyTag()).getOrThrow();
        return decoder.decode(ops, map);
    }
}
