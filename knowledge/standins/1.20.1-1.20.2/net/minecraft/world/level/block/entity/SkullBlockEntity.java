package mmt.standin.net.minecraft.world.level.block.entity;

import com.mojang.authlib.GameProfile;
import java.util.function.Consumer;
import net.minecraft.Util;

/**
 * 1.20.1's SkullBlockEntity.updateGameprofile on 1.20.2.
 *
 * Read from both releases: 1.20.1's updateGameprofile(profile, then) handed then the profile as it was when it had no
 * name or already had textures, and otherwise looked the name up in the profile cache, filled in its textures from the
 * session service, added it to the cache and handed then the filled profile on the main thread (the profile as it was
 * where the name was not found). 1.20.2 split it: fetchGameProfile(name) does the lookup, filling and caching, its result
 * on the main thread (Optional.empty() with no cache or nothing found), and updateOwnerProfile applies it with
 * orElse(owner) where the profile has a name and no textures (hasTextures). So the old call is that test, then the fetch
 * with the profile as it was where nothing was found.
 *
 * NeoForge's primers begin at 1.20.2 -> 1.20.3, and Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html) does
 * not mention skulls; this is from the game's code alone.
 */
public final class SkullBlockEntity {

    private SkullBlockEntity() {}

    public static void updateGameprofile(GameProfile profile, Consumer then) {
        if (profile != null && !Util.isBlank(profile.getName()) && !profile.getProperties().containsKey("textures")) {
            net.minecraft.world.level.block.entity.SkullBlockEntity.fetchGameProfile(profile.getName()).thenAccept(found -> then.accept(((java.util.Optional) found).orElse(profile)));
        } else {
            then.accept(profile);
        }
    }
}
