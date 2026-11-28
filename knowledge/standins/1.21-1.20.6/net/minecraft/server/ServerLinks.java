package mmt.standin.net.minecraft.server;

/**
 * 1.21's ServerLinks on 1.20.6, for a port back: the class itself, which 1.20.6 does not have yet; EMPTY and the links a
 * server sends, which 1.20.6's servers never send, so a client there only ever holds EMPTY.
 *
 * Read from 1.21: the record (entries), EMPTY with none, isEmpty(); CommonListenerCookie carries one (see that stand-in).
 * 1.20.6 has no server links, and no packet carries them.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`net.minecraft.server.MinecraftServer#serverLinks` - A list of entries indicating what the link coming from the server is".
 */
public final class ServerLinks {

    public static final ServerLinks EMPTY = new ServerLinks(java.util.List.of());

    private final java.util.List entries;

    public ServerLinks(java.util.List entries) {
        this.entries = entries;
    }

    public java.util.List entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
