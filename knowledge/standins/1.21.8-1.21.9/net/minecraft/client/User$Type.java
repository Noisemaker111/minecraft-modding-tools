package mmt.standin.net.minecraft.client;

import java.util.Locale;

/**
 * 1.21.8's User.Type, which 1.21.9 removed.
 *
 * Read from both releases: 1.21.8's User carried the kind of account it signed in with, the enum LEGACY("legacy"),
 * MOJANG("mojang"), MSA("msa") (byName(name), case-insensitive, null for an unknown one; getName()). 1.21.9's User takes
 * and keeps no kind: the game signs in with Microsoft accounts only, so every user is what 1.21.8 called MSA (see
 * User's stand-in).
 *
 * Agrees with NeoForge's 1.21.8 -> 1.21.9 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.9/index.md, CC BY 4.0):
 * "User no longer takes in the user type"; removed: "User#getType, $Type".
 */
public enum User$Type {
    LEGACY("legacy"),
    MOJANG("mojang"),
    MSA("msa");

    private final String name;

    User$Type(String name) {
        this.name = name;
    }

    public static User$Type byName(String name) {
        for (User$Type type : values()) {
            if (type.name.equals(name.toLowerCase(Locale.ROOT))) {
                return type;
            }
        }
        return null;
    }

    public String getName() {
        return this.name;
    }
}
