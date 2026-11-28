package mmt.standin.net.neoforged.neoforge.client.settings;

/**
 * NeoForge 21.0's KeyModifier.getKeyModifier(InputConstants.Key) (21.0.167) on 20.6 (20.6.119), for a port back.
 *
 * Read from both loaders' sources: 21.0's getKeyModifier returns the first of MODIFIER_VALUES (SHIFT, CONTROL, ALT)
 * whose matches(key) is true, else NONE, and 21.0's isKeyCodeModifier(key) is getKeyModifier(key) != NONE. 20.6 has the
 * same MODIFIER_VALUES and public matches(key), and its isKeyCodeModifier asks each the same way. NeoForge's primers
 * cover the game, not the loader; this follows the two loaders' own code.
 */
public final class KeyModifier {

    private KeyModifier() {}

    public static net.neoforged.neoforge.client.settings.KeyModifier getKeyModifier(com.mojang.blaze3d.platform.InputConstants.Key key) {
        for (net.neoforged.neoforge.client.settings.KeyModifier modifier : net.neoforged.neoforge.client.settings.KeyModifier.MODIFIER_VALUES) {
            if (modifier.matches(key)) {
                return modifier;
            }
        }
        return net.neoforged.neoforge.client.settings.KeyModifier.NONE;
    }
}
