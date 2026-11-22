package mmt.standin.net.minecraft.server.packs.resources;

import java.util.Map;
import java.util.function.Predicate;

/**
 * 26.2's ResourceManager on 26.3.
 *
 * Read from both releases: 26.2's listResources(path, filter) and listResourceStacks(path, filter) listed the resources
 * under a path whose identifier the filter (a Predicate of Identifier) accepted. 26.3 takes a ResourceManager.Selector in
 * place of the predicate, whose one method isIncluded(Identifier) asks the same question. So each old method is the new
 * one with a selector that asks the predicate.
 *
 * Agrees with NeoForge's 26.2 -> 26.3 primer (https://github.com/neoforged/.github/blob/main/primers/26.3/index.md, CC BY 4.0): "listResources, listResourceStacks now tak in a ResourceManager$Selector instead of an Identifier predicate".
 */
public final class ResourceManager {

    private ResourceManager() {}

    public static Map listResources(net.minecraft.server.packs.resources.ResourceManager self, String path, Predicate filter) {
        return self.listResources(path, filter::test);
    }

    public static Map listResourceStacks(net.minecraft.server.packs.resources.ResourceManager self, String path, Predicate filter) {
        return self.listResourceStacks(path, filter::test);
    }
}
