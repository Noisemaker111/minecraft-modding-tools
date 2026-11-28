package mmt.standin.net.minecraft.server;

import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.20.1's ServerAdvancementManager.getAdvancement on 1.20.2.
 *
 * Read from both releases: 1.20.1's getAdvancement(id) returned the loaded Advancement with that id, or null; the
 * advancement knew its own id (getId()). 1.20.2 made Advancement a record without its id and keeps each one with its id in
 * an AdvancementHolder: get(id) returns the holder, or null, and holder.value() is the advancement.
 *
 * Agrees with Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html): "RecipeEntry or AdvancementEntry is used in
 * many places where a raw recipe or advancement is used previously. As the name suggests, this is a pair of the ID and the
 * value. If you want to get the recipe/advancement itself, call .value()." (Yarn's AdvancementEntry is Mojang's
 * AdvancementHolder.)
 */
public final class ServerAdvancementManager {

    private ServerAdvancementManager() {}

    public static Advancement getAdvancement(net.minecraft.server.ServerAdvancementManager self, ResourceLocation id) {
        AdvancementHolder holder = self.get(id);
        return holder == null ? null : holder.value();
    }
}
