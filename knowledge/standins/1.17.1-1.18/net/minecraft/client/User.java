package mmt.standin.net.minecraft.client;

import java.util.Optional;

/**
 * 1.17.1's User(name, uuid, accessToken, type) constructor on 1.18.
 *
 * Read from both releases: 1.17.1's User held a name, uuid, access token and a type read with User.Type.byName. 1.18
 * added the Xbox user id and the client id, each an Optional, before the type, which it takes as a User.Type; its Main
 * passes an empty Optional for either when the launcher gave none. This makes 1.18's User with both empty.
 *
 * Fabric's 1.18 post (https://fabricmc.net/2021/11/30/118.html) does not mention it; this is from the game's code alone.
 */
public final class User {

    private User() {}

    public static net.minecraft.client.User init(String name, String uuid, String accessToken, String type) {
        return new net.minecraft.client.User(name, uuid, accessToken, Optional.empty(), Optional.empty(), net.minecraft.client.User.Type.byName(type));
    }
}
