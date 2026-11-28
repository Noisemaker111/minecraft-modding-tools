package mmt.standin.net.minecraft.server;

import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;

/**
 * 1.20.1's PlayerAdvancements.getOrStartProgress(Advancement) on 1.20.2.
 *
 * Read from both releases: 1.20.1's getOrStartProgress(advancement) returned the player's progress on it, starting one from
 * its requirements where there was none. 1.20.2 keys progress by AdvancementHolder, the advancement with its id
 * (getOrStartProgress(holder)), and the server's ServerAdvancementManager holds every loaded one
 * (getAllAdvancements()). So the old call is the new one on the holder of that advancement on the player's server; an
 * advancement the server has not loaded gets a fresh progress on its requirements, as 1.20.1 started one, not kept.
 *
 * Agrees with Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html): "RecipeEntry or AdvancementEntry is used in
 * many places where a raw recipe or advancement is used previously." (Yarn's AdvancementEntry is Mojang's
 * AdvancementHolder.)
 */
public final class PlayerAdvancements {

    private PlayerAdvancements() {}

    public static AdvancementProgress getOrStartProgress(net.minecraft.server.PlayerAdvancements self, Advancement advancement) {
        for (Object each : self.player.getServer().getAdvancements().getAllAdvancements()) {
            AdvancementHolder holder = (AdvancementHolder) each;
            if (holder.value() == advancement) {
                return self.getOrStartProgress(holder);
            }
        }
        AdvancementProgress progress = new AdvancementProgress();
        progress.update(advancement.requirements());
        return progress;
    }
}
