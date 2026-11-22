package mmt.standin.net.minecraft.server.packs;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.util.GsonHelper;
import org.slf4j.Logger;

/**
 * 1.21.8's AbstractPackResources on 1.21.9.
 *
 * Read from both releases: 1.21.8's static getMetadataFromStream(type, stream) parsed the stream as a JSON object, and gave
 * the type's section decoded with its codec, or null when the stream did not parse, had no such section, or the section
 * did not decode (logging each failure). 1.21.9's getMetadataFromStream(type, stream, location) does the same steps; the
 * pack's location only names the pack in its log lines. The same steps, logging without a pack name.
 *
 * NeoForge's 1.21.8 -> 1.21.9 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.9/index.md, CC BY 4.0) does not list this change; it is from the game's code alone.
 */
public final class AbstractPackResources {

    private static final Logger LOGGER = LogUtils.getLogger();

    private AbstractPackResources() {}

    public static Object getMetadataFromStream(MetadataSectionType type, InputStream stream) {
        JsonObject json;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            json = GsonHelper.parse(reader);
        } catch (Exception e) {
            LOGGER.error("Couldn't load {} metadata", type.name(), e);
            return null;
        }
        if (!json.has(type.name())) {
            return null;
        }
        return type.codec().parse(JsonOps.INSTANCE, json.get(type.name()))
            .ifError(error -> LOGGER.error("Couldn't load {} metadata: {}", type.name(), error))
            .result()
            .orElse(null);
    }
}
