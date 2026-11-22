package mmt.standin.net.minecraft.server.packs.resources;

import java.io.IOException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

/**
 * 1.18.2's ResourceProvider.getResource(location) on 1.19.
 *
 * Read from both releases: 1.18.2's getResource(location) returned the resource or threw an IOException (a
 * FileNotFoundException naming the location when no pack had it). 1.19's returns an Optional<Resource>, and
 * getResourceOrThrow(location) is the old contract: the resource, or a FileNotFoundException naming the location.
 *
 * Fabric's 1.19 post (https://fabricmc.net/2022/06/07/119.html) does not mention getResource, and NeoForge's primers begin at 1.20.2 -> 1.20.3; this is from the game's code alone.
 */
public final class ResourceProvider {

    private ResourceProvider() {}

    public static Resource getResource(net.minecraft.server.packs.resources.ResourceProvider self, ResourceLocation location) throws IOException {
        return self.getResourceOrThrow(location);
    }
}
