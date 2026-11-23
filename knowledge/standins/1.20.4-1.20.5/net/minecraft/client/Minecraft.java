package mmt.standin.net.minecraft.client;

/**
 * 1.20.4's Minecraft on 1.20.5.
 *
 * Read from both releases: 1.20.4's is64Bit() gave a flag set once at start by checkIs64Bit(), true when the first of
 * the system properties sun.arch.data.model, com.ibm.vm.bitmode and os.arch that is set contains "64". 1.20.5 removed
 * the flag and the check; the JVM's properties are still there to read the same way.
 *
 * Agrees with NeoForge's 1.20.4 -> 1.20.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.20.5/index.md, CC BY 4.0): Removals, "net.minecraft.client.Minecraft#is64Bit".
 */
public final class Minecraft {

    private Minecraft() {}

    public static boolean is64Bit(net.minecraft.client.Minecraft self) {
        for (String key : new String[] { "sun.arch.data.model", "com.ibm.vm.bitmode", "os.arch" }) {
            String value = System.getProperty(key);
            if (value != null && value.contains("64")) {
                return true;
            }
        }
        return false;
    }
}
