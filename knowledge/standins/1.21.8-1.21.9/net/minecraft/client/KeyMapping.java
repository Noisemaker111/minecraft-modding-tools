package mmt.standin.net.minecraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Locale;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.21.8's KeyMapping on 1.21.9.
 *
 * Read from both releases: 1.21.8's matches(key, scancode) compared a scancode binding with the scancode when the key was
 * unknown (-1), else a key binding with the key; matchesMouse(button) compared a mouse binding with the button. 1.21.9's
 * matches(KeyEvent) and matchesMouse(MouseButtonEvent) make the same comparisons with the event's key, scancode and
 * button, and read nothing else of it (not the modifiers or the cursor position).
 * 1.21.8's constructors took the category as a translation key ("key.categories.misc" and the other vanilla keys, or a
 * mod's own); 1.21.9's take a KeyMapping.Category, a registered id whose label is the translation key
 * "key.category.NAMESPACE.PATH" (vanilla's are minecraft:movement, misc, multiplayer, gameplay, inventory, creative,
 * spectator). A vanilla key is its category ("key.categories.ui", which 1.21.9 dropped, is misc); a mod's key
 * "key.category.NAMESPACE.PATH" is the category NAMESPACE:PATH, whose label is the same key; any other key is a category
 * named by what follows "key.category." or "key.categories." (minecraft: when it has no namespace), whose label is
 * untranslated where 1.21.8 showed the mod's translation. A category is registered once; registering it again (Category.register refuses) gives the same record.
 *
 * Agrees with NeoForge's 1.21.8 -> 1.21.9 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.9/index.md, CC BY 4.0): "KeyMapping now takes in a $Category instead of a String", "CATEGORY_* -> $Category#*", "matches now takes in a KeyEvent instead of the key and scancode ints", "matchesMouse now takes in a MouseButtonEvent instead of the button int".
 */
public final class KeyMapping {

    private KeyMapping() {}

    public static boolean matches(net.minecraft.client.KeyMapping self, int key, int scancode) {
        return self.matches(new KeyEvent(key, scancode, 0));
    }

    public static boolean matchesMouse(net.minecraft.client.KeyMapping self, int button) {
        return self.matchesMouse(new MouseButtonEvent(0.0, 0.0, new MouseButtonInfo(button, 0)));
    }

    public static net.minecraft.client.KeyMapping init(String name, int key, String category) {
        return new net.minecraft.client.KeyMapping(name, key, category(category));
    }

    public static net.minecraft.client.KeyMapping init(String name, InputConstants.Type type, int key, String category) {
        return new net.minecraft.client.KeyMapping(name, type, key, category(category));
    }

    private static net.minecraft.client.KeyMapping.Category category(String key) {
        switch (key) {
            case "key.categories.movement":
                return net.minecraft.client.KeyMapping.Category.MOVEMENT;
            case "key.categories.misc":
            case "key.categories.ui":
                return net.minecraft.client.KeyMapping.Category.MISC;
            case "key.categories.multiplayer":
                return net.minecraft.client.KeyMapping.Category.MULTIPLAYER;
            case "key.categories.gameplay":
                return net.minecraft.client.KeyMapping.Category.GAMEPLAY;
            case "key.categories.inventory":
                return net.minecraft.client.KeyMapping.Category.INVENTORY;
            case "key.categories.creative":
                return net.minecraft.client.KeyMapping.Category.CREATIVE;
            default:
                break;
        }
        String rest = key.startsWith("key.category.") ? key.substring("key.category.".length())
            : key.startsWith("key.categories.") ? key.substring("key.categories.".length()) : key;
        int dot = rest.indexOf('.');
        ResourceLocation id = dot > 0
            ? ResourceLocation.fromNamespaceAndPath(clean(rest.substring(0, dot), false), clean(rest.substring(dot + 1), true))
            : ResourceLocation.fromNamespaceAndPath("minecraft", clean(rest, true));
        try {
            return net.minecraft.client.KeyMapping.Category.register(id);
        } catch (IllegalArgumentException registered) {
            return new net.minecraft.client.KeyMapping.Category(id);
        }
    }

    private static String clean(String part, boolean path) {
        StringBuilder out = new StringBuilder();
        for (char c : part.toLowerCase(Locale.ROOT).toCharArray()) {
            boolean ok = c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '.' || path && c == '/';
            out.append(ok ? c : '_');
        }
        return out.isEmpty() ? "_" : out.toString();
    }
}
