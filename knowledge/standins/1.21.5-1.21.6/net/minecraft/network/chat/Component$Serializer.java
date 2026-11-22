package mmt.standin.net.minecraft.network.chat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.mojang.serialization.JsonOps;
import java.io.StringReader;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;

/**
 * 1.21.5's Component.Serializer, which 1.21.6 dropped.
 *
 * Read from both releases: 1.21.5's Serializer read and wrote components as JSON through ComponentSerialization.CODEC
 * with the registries' JsonOps context (getOrThrow(JsonParseException::new)), fromJson giving null for no JSON and
 * fromJsonLenient reading leniently, toJson writing with a Gson that does not escape HTML. 1.21.6 removed the class and
 * keeps ComponentSerialization.CODEC and HolderLookup.Provider.createSerializationContext unchanged, so these are the
 * same reads and writes.
 *
 * Agrees with NeoForge's 1.21.5 -> 1.21.6 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.6/index.md, CC BY 4.0), List of Removals: "net.minecraft.network.chat.Component$Serializer, $SerializerAdapter".
 */
public final class Component$Serializer {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Component$Serializer() {}

    private static MutableComponent deserialize(JsonElement json, HolderLookup.Provider registries) {
        return (MutableComponent) ComponentSerialization.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), json).getOrThrow(message -> new JsonParseException(String.valueOf(message)));
    }

    public static String toJson(Component component, HolderLookup.Provider registries) {
        return GSON.toJson((JsonElement) ComponentSerialization.CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), component).getOrThrow(message -> new JsonParseException(String.valueOf(message))));
    }

    public static MutableComponent fromJson(String json, HolderLookup.Provider registries) {
        JsonElement element = JsonParser.parseString(json);
        return element == null ? null : deserialize(element, registries);
    }

    public static MutableComponent fromJson(JsonElement json, HolderLookup.Provider registries) {
        return json == null ? null : deserialize(json, registries);
    }

    public static MutableComponent fromJsonLenient(String json, HolderLookup.Provider registries) {
        JsonReader reader = new JsonReader(new StringReader(json));
        reader.setLenient(true);
        JsonElement element = JsonParser.parseReader(reader);
        return element == null ? null : deserialize(element, registries);
    }
}
