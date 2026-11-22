package mmt.standin.net.minecraft.world.level.block.state;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * 1.21.11's StateHolder on 26.1.
 *
 * Read from both releases: 1.21.11's StateHolder kept its properties in one Reference2ObjectArrayMap and getValues()
 * returned that map, in the block's property order, the same map on every call. 26.1 keeps two arrays (propertyKeys,
 * propertyValues) and getValues() returns a Stream of Property.Value (property, value) in that order. So the old
 * getValues is the stream's pairs in an unmodifiable map that keeps their order, made once per state and kept; a state's
 * values never change.
 *
 * Agrees with NeoForge's 1.21.11 -> 26.1 primer (https://github.com/neoforged/.github/blob/main/primers/26.1/index.md, CC BY 4.0): "StateHolder now takes in an array of Propertys and Comparable values instead of one value map" and "getValues now returns a stream of Property$Value s".
 */
public final class StateHolder {

    private static final Map<net.minecraft.world.level.block.state.StateHolder, Map> VALUES = Collections.synchronizedMap(new WeakHashMap<>());

    private StateHolder() {}

    public static Map getValues(net.minecraft.world.level.block.state.StateHolder self) {
        return VALUES.computeIfAbsent(self, state -> {
            Map values = new LinkedHashMap();
            Iterator it = state.getValues().iterator();
            while (it.hasNext()) {
                Property.Value value = (Property.Value) it.next();
                values.put(value.property(), value.value());
            }
            return Collections.unmodifiableMap(values);
        });
    }
}
