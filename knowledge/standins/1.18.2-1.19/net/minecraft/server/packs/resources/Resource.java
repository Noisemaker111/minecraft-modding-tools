package mmt.standin.net.minecraft.server.packs.resources;

import java.io.IOException;
import java.io.InputStream;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;

/**
 * 1.18.2's Resource on 1.19's.
 *
 * Read from both releases: 1.18.2's Resource was an interface whose getInputStream() was the stream the pack opened for
 * it, getSourceName() the pack's id, hasMetadata() whether a .mcmeta file came with it and getMetadata(serializer) that
 * file's section (or null); it was Closeable, closing that stream. 1.19's is a class: open() opens a new stream each call (the
 * caller closes it), sourcePackId() is the pack's id, and metadata() reads the .mcmeta file's sections. These are the old
 * members on those: getInputStream() opens one (the caller closes it, as 1.19's callers do); hasMetadata() is
 * whether metadata() is anything but EMPTY.
 *
 * Fabric's 1.19 post (https://fabricmc.net/2022/06/07/119.html) does not mention Resource, and NeoForge's primers begin at 1.20.2 -> 1.20.3; this is from the game's code alone.
 */
public final class Resource {

    private Resource() {}

    public static InputStream getInputStream(net.minecraft.server.packs.resources.Resource self) throws IOException {
        return self.open();
    }

    public static String getSourceName(net.minecraft.server.packs.resources.Resource self) {
        return self.sourcePackId();
    }

    public static boolean hasMetadata(net.minecraft.server.packs.resources.Resource self) throws IOException {
        return self.metadata() != net.minecraft.server.packs.resources.ResourceMetadata.EMPTY;
    }

    public static Object getMetadata(net.minecraft.server.packs.resources.Resource self, MetadataSectionSerializer serializer) throws IOException {
        return self.metadata().getSection(serializer).orElse(null);
    }
}
