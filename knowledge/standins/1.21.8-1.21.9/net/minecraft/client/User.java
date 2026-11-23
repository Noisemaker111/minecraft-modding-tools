package mmt.standin.net.minecraft.client;

/**
 * 1.21.8's User.getType() on 1.21.9.
 *
 * Read from both releases: 1.21.8's getType() answered the kind of account the user signed in with (User.Type). 1.21.9
 * signs in only with Microsoft accounts and keeps no kind, so every 1.21.9 user is an MSA one.
 *
 * Agrees with NeoForge's 1.21.8 -> 1.21.9 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.9/index.md, CC BY 4.0):
 * "User no longer takes in the user type"; removed: "User#getType, $Type".
 */
public final class User {

    private User() {}

    public static mmt.standin.net.minecraft.client.User$Type getType(net.minecraft.client.User self) {
        return mmt.standin.net.minecraft.client.User$Type.MSA;
    }
}
