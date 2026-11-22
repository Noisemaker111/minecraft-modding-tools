package mmt.standin.net.neoforged.neoforge.client.settings;

/**
 * NeoForge 21.4's KeyModifier (21.4.161) on 21.5 (21.5.101).
 *
 * Read from both loaders' files: 21.4's getActiveModifier(), deprecated for removal since 1.21.4, gave the first of
 * MODIFIER_VALUES (SHIFT, CONTROL, ALT) whose isActive(null) held, else NONE; 21.4 added getActiveModifiers(), every
 * active one in the same order. 21.5 drops getActiveModifier() and keeps getActiveModifiers() and NONE unchanged, so
 * the old answer is the first of the list, or NONE.
 *
 * Loader files only: NeoForge's game primer covers vanilla classes, not the loader; 21.4 marked the method
 * @Deprecated(forRemoval = true, since = "1.21.4") beside getActiveModifiers().
 */
public final class KeyModifier {

    private KeyModifier() {}

    public static net.neoforged.neoforge.client.settings.KeyModifier getActiveModifier() {
        java.util.List active = net.neoforged.neoforge.client.settings.KeyModifier.getActiveModifiers();
        return active.isEmpty() ? net.neoforged.neoforge.client.settings.KeyModifier.NONE : (net.neoforged.neoforge.client.settings.KeyModifier) active.get(0);
    }
}
