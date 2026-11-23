package mmt.standin.net.minecraft.network;

/**
 * 26.1.2's Connection.isEncrypted() and its encrypted field on 26.2.
 *
 * Read from both releases: 26.1.2's Connection set encrypted to true in setEncryptionKey(decrypt, encrypt), the one place
 * that adds the "decrypt" and "encrypt" cipher handlers to the channel's pipeline, and isEncrypted() answered it. 26.2's
 * setEncryptionKey adds the same two handlers under the same names and keeps no flag, so a connection is encrypted when its
 * channel's pipeline holds the "decrypt" handler.
 *
 * Agrees with NeoForge's 26.1 -> 26.2 primer (https://github.com/neoforged/.github/blob/main/primers/26.2/index.md, CC BY 4.0):
 * "net.minecraft.network.Connection: isEncrypted is removed".
 */
public final class Connection {

    private Connection() {}

    public static boolean isEncrypted(net.minecraft.network.Connection self) {
        return self.channel != null && self.channel.pipeline().get("decrypt") != null;
    }

    public static boolean encrypted(net.minecraft.network.Connection self) {
        return isEncrypted(self);
    }
}
