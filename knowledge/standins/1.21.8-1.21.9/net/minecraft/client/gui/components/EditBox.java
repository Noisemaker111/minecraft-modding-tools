package mmt.standin.net.minecraft.client.gui.components;

import java.util.function.BiFunction;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.FormattedCharSequence;

/**
 * 1.21.8's EditBox on 1.21.9.
 *
 * Read from both releases: 1.21.8's keyPressed(key, scancode, modifiers) and charTyped(char, modifiers) handled one key
 * press or typed character; 1.21.9's keyPressed(KeyEvent) and charTyped(CharacterEvent) handle the same input carried in
 * one record (KeyEvent(key, scancode, modifiers), CharacterEvent(codepoint, modifiers)) with the same steps.
 * 1.21.8's setFormatter replaced the box's one formatter (by default each string unstyled); 1.21.9 keeps a list of
 * formatters, asks each in turn and uses the first answer that is not null, and falls back to the string unstyled when
 * none answers. Setting a formatter is the list holding only it.
 *
 * Agrees with NeoForge's 1.21.8 -> 1.21.9 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.9/index.md, CC BY 4.0): "keyPressed now takes in a KeyEvent", "charTyped now takes in a CharacterEvent", "EditBox#setFormatter -> addFormatter, not one-to-one".
 */
public final class EditBox {

    private EditBox() {}

    public static boolean keyPressed(net.minecraft.client.gui.components.EditBox self, int key, int scancode, int modifiers) {
        return self.keyPressed(new KeyEvent(key, scancode, modifiers));
    }

    public static boolean charTyped(net.minecraft.client.gui.components.EditBox self, char codepoint, int modifiers) {
        return self.charTyped(new CharacterEvent(codepoint, modifiers));
    }

    public static void setFormatter(net.minecraft.client.gui.components.EditBox self, BiFunction formatter) {
        self.formatters.clear();
        self.addFormatter((text, cursor) -> (FormattedCharSequence) formatter.apply(text, cursor));
    }
}
