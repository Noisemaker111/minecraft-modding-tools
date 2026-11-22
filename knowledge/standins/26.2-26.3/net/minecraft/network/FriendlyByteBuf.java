package mmt.standin.net.minecraft.network;

import java.util.Iterator;
import java.util.Map;
import net.minecraft.network.codec.StreamEncoder;

/**
 * 26.2's FriendlyByteBuf on 26.3.
 *
 * Read from both releases: 26.2's writeMap(map, keyEncoder, valueEncoder) wrote the map's size as a VarInt, then each
 * entry's key and value with the two encoders. 26.3 drops readMap and writeMap (maps go through stream codecs) and keeps
 * writeVarInt. So the old method writes the same bytes.
 *
 * Agrees with NeoForge's 26.2 -> 26.3 primer (https://github.com/neoforged/.github/blob/main/primers/26.3/index.md, CC BY 4.0), whose list of removals names "FriendlyByteBuf: readMap, writeMap".
 */
public final class FriendlyByteBuf {

    private FriendlyByteBuf() {}

    public static void writeMap(net.minecraft.network.FriendlyByteBuf self, Map map, StreamEncoder keyEncoder, StreamEncoder valueEncoder) {
        self.writeVarInt(map.size());
        Iterator it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry entry = (Map.Entry) it.next();
            keyEncoder.encode(self, entry.getKey());
            valueEncoder.encode(self, entry.getValue());
        }
    }
}
