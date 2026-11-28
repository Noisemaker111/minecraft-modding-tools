package mmt.standin.net.minecraft.client;

import com.mojang.authlib.GameProfile;

/**
 * 1.20.1's User.getGameProfile() on 1.20.2.
 *
 * Read from both releases: 1.20.1's User.getGameProfile() was new GameProfile(getProfileId(), getName()), the session's id
 * and name with no properties. 1.20.2's User keeps the id as a UUID (getProfileId()) and the name (getName()) and dropped
 * getGameProfile; the game asks Minecraft.getGameProfile() for the profile with its properties instead. The old call is
 * the same two-part profile made from what User still has.
 *
 * NeoForge's primers begin at 1.20.2 -> 1.20.3, and Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html) does
 * not mention User; this is from the game's code alone.
 */
public final class User {

    private User() {}

    public static GameProfile getGameProfile(net.minecraft.client.User self) {
        return new GameProfile(self.getProfileId(), self.getName());
    }
}
