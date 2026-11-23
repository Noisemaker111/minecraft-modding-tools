package mmt.standin.net.minecraft.commands;

import net.minecraft.commands.PermissionSource;

/**
 * 1.21.5's SharedSuggestionProvider.hasPermission(level) on 1.21.6.
 *
 * Read from both releases: 1.21.5's SharedSuggestionProvider declared hasPermission(int), which its two game
 * implementations answered (CommandSourceStack by its permission level, ClientSuggestionProvider by whether the player may
 * use restricted commands, or the level is 0). 1.21.6 moved the method to a new interface, PermissionSource, which both
 * of those classes now implement beside SharedSuggestionProvider with the same bodies. So a source that is a
 * PermissionSource answers as before; one that is not (a mod's own provider) has no permission level to ask, and gets no
 * permission above 0, as a client without restricted commands did.
 *
 * Agrees with NeoForge's 1.21.5 -> 1.21.6 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.6/index.md, CC BY 4.0): "The permission checks for commands have been abstracted into its own PermissionSource interface. This provides the previously provided hasPermission method", and under SharedSuggestionProvider "hasPermission is removed".
 */
public final class SharedSuggestionProvider {

    private SharedSuggestionProvider() {}

    public static boolean hasPermission(net.minecraft.commands.SharedSuggestionProvider self, int level) {
        if (self instanceof PermissionSource source) {
            return source.hasPermission(level);
        }
        return level <= 0;
    }
}
