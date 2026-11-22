package mmt.standin.net.minecraft.server.packs.resources;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.18.2's ResourceManager.hasResource and listResources on 1.19.
 *
 * Read from both releases: 1.18.2's hasResource(location) was whether any pack had the file. Its listResources(path,
 * filter) walked every pack's files under path, kept those whose file name (the last part of the path, .mcmeta files
 * left out) the filter accepted, and returned their locations once each, sorted. 1.19 dropped hasResource
 * (getResource(location) is an Optional, present when a pack has the file) and its listResources(path, filter) tests
 * the whole location and returns a map from location to resource, .mcmeta files left out. The old listing is the new
 * one's keys whose file name the old filter accepts, sorted.
 *
 * Fabric's 1.19 post (https://fabricmc.net/2022/06/07/119.html) does not mention hasResource or listResources, and NeoForge's primers begin at 1.20.2 -> 1.20.3; this is from the game's code alone.
 */
public final class ResourceManager {

    private ResourceManager() {}

    public static boolean hasResource(net.minecraft.server.packs.resources.ResourceManager self, ResourceLocation location) {
        return self.getResource(location).isPresent();
    }

    public static Collection listResources(net.minecraft.server.packs.resources.ResourceManager self, String path, Predicate filter) {
        List<ResourceLocation> found = new ArrayList<>();
        for (Object key : self.listResources(path, location -> {
            String full = ((ResourceLocation) location).getPath();
            return filter.test(full.substring(full.lastIndexOf('/') + 1));
        }).keySet()) {
            found.add((ResourceLocation) key);
        }
        Collections.sort(found);
        return found;
    }
}
