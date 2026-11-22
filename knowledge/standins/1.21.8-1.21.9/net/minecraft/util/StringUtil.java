package mmt.standin.net.minecraft.util;

/**
 * 1.21.8's StringUtil on 1.21.9.
 *
 * Read from both releases: 1.21.8's isAllowedChatCharacter(char) and 1.21.9's isAllowedChatCharacter(int) make the same
 * test (not the section sign 167, not below space, not delete 127); 1.21.9 takes the character as a code point.
 *
 * Agrees with NeoForge's 1.21.8 -> 1.21.9 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.9/index.md, CC BY 4.0), where typed characters arrive as a CharacterEvent's code point.
 */
public final class StringUtil {

    private StringUtil() {}

    public static boolean isAllowedChatCharacter(char c) {
        return net.minecraft.util.StringUtil.isAllowedChatCharacter((int) c);
    }
}
