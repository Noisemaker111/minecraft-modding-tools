package mmt.standin.net.minecraft.network;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * 1.18.2's FriendlyByteBuf collection reads and writes on 1.19.
 *
 * Read from both releases: 1.18.2's readCollection, readList, readMap, readOptional, writeCollection, writeMap and
 * writeOptional took plain java.util.function Functions and BiConsumers to read or write each element; 1.19's take
 * FriendlyByteBuf.Reader and FriendlyByteBuf.Writer, which extend Function<FriendlyByteBuf, T> and BiConsumer<
 * FriendlyByteBuf, T> and are read and written the same way. Each old call is the new one with the given function as a
 * Reader or Writer.
 *
 * Fabric's 1.19 post (https://fabricmc.net/2022/06/07/119.html) does not mention FriendlyByteBuf, and NeoForge's primers begin at 1.20.2 -> 1.20.3; this is from the game's code alone.
 */
public final class FriendlyByteBuf {

    private FriendlyByteBuf() {}

    public static Collection readCollection(net.minecraft.network.FriendlyByteBuf self, IntFunction factory, Function reader) {
        return self.readCollection(factory, reader::apply);
    }

    public static List readList(net.minecraft.network.FriendlyByteBuf self, Function reader) {
        return self.readList(reader::apply);
    }

    public static Map readMap(net.minecraft.network.FriendlyByteBuf self, IntFunction factory, Function keys, Function values) {
        return self.readMap(factory, keys::apply, values::apply);
    }

    public static Map readMap(net.minecraft.network.FriendlyByteBuf self, Function keys, Function values) {
        return self.readMap(keys::apply, values::apply);
    }

    public static Optional readOptional(net.minecraft.network.FriendlyByteBuf self, Function reader) {
        return self.readOptional(reader::apply);
    }

    public static void writeCollection(net.minecraft.network.FriendlyByteBuf self, Collection values, BiConsumer writer) {
        self.writeCollection(values, writer::accept);
    }

    public static void writeMap(net.minecraft.network.FriendlyByteBuf self, Map map, BiConsumer keys, BiConsumer values) {
        self.writeMap(map, keys::accept, values::accept);
    }

    public static void writeOptional(net.minecraft.network.FriendlyByteBuf self, Optional value, BiConsumer writer) {
        self.writeOptional(value, writer::accept);
    }
}
