package mmt.standin.net.minecraft.server.packs;

import com.mojang.logging.LogUtils;
import java.io.InputStream;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.resources.ResourceMetadata;
import org.slf4j.Logger;

/**
 * 1.21.11's AbstractPackResources on 26.1.
 *
 * Read from both releases: 1.21.11's static getMetadataFromStream(type, stream, location) parsed the stream as JSON and
 * decoded the section named type.name() with type.codec(), logging and returning null when the JSON or the section did not
 * read, and null when the section was not there. 26.1 drops it: AbstractPackResources.loadMetadata reads pack.mcmeta with
 * ResourceMetadata.fromJsonStream(stream) and asks it getSection(type), which decodes that section the same way. So the old
 * method is those two, with 1.21.11's null and log where reading fails.
 *
 * Agrees with NeoForge's 1.21.11 -> 26.1 primer (https://github.com/neoforged/.github/blob/main/primers/26.1/index.md, CC BY 4.0), whose list of removals names "net.minecraft.server.packs.AbstractPackResources#getMetadataFromStream".
 */
public final class AbstractPackResources {

    private static final Logger LOGGER = LogUtils.getLogger();

    private AbstractPackResources() {}

    public static Object getMetadataFromStream(MetadataSectionType type, InputStream stream, PackLocationInfo location) {
        try {
            return ResourceMetadata.fromJsonStream(stream).getSection(type).orElse(null);
        } catch (Exception e) {
            LOGGER.error("Couldn't load {} {} metadata: {}", location.id(), type.name(), e.getMessage());
            return null;
        }
    }
}
