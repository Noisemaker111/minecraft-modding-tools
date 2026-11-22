package mmt.standin.net.minecraft.resources;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import java.lang.reflect.Type;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;

/**
 * 1.21.4's ResourceLocation.Serializer, which 1.21.5 dropped.
 *
 * Read from both releases: 1.21.4's Serializer was a Gson adapter that read a location with
 * ResourceLocation.parse(GsonHelper.convertToString(json, "location")) and wrote it as its string. 1.21.5 removed the
 * class (the game reads locations through ResourceLocation.CODEC); ResourceLocation.parse and GsonHelper.convertToString
 * are unchanged, so this is the same adapter.
 *
 * Agrees with NeoForge's 1.21.4 -> 1.21.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.5/index.md, CC BY 4.0), List of Removals: "net.minecraft.resources.ResourceLocation$Serializer".
 */
public class ResourceLocation$Serializer implements JsonDeserializer, JsonSerializer {

    public ResourceLocation$Serializer() {}

    @Override
    public Object deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
        return ResourceLocation.parse(GsonHelper.convertToString(json, "location"));
    }

    @Override
    public JsonElement serialize(Object location, Type type, JsonSerializationContext context) {
        return new JsonPrimitive(location.toString());
    }
}
