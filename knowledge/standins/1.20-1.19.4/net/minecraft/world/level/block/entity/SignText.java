package mmt.standin.net.minecraft.world.level.block.entity;

import java.util.Arrays;
import java.util.function.Function;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;

/**
 * 1.20's SignText on 1.19.4: the class itself, which 1.19.4 does not have.
 *
 * Read from both releases: 1.20 moved a sign's four lines (as written and as filtered), its dye color and its glowing
 * flag out of SignBlockEntity into SignText, an unchangeable value each setter copies (setMessage, setColor,
 * setHasGlowingText), one for each side. 1.19.4's SignBlockEntity keeps exactly those fields for its one side
 * (messages, filteredMessages, color, hasGlowingText). This is 1.20's value with its methods as 1.20 writes them;
 * SignBlockEntity's stand-in makes one from a 1.19.4 sign and writes one back. Not here: the codec.
 */
public class SignText {

    public static final int LINES = 4;

    private final Component[] messages;
    private final Component[] filteredMessages;
    private final DyeColor color;
    private final boolean hasGlowingText;
    private FormattedCharSequence[] renderMessages;
    private boolean renderMessagedFiltered;

    public SignText() {
        this(emptyMessages(), emptyMessages(), DyeColor.BLACK, false);
    }

    public SignText(Component[] messages, Component[] filteredMessages, DyeColor color, boolean hasGlowingText) {
        this.messages = messages;
        this.filteredMessages = filteredMessages;
        this.color = color;
        this.hasGlowingText = hasGlowingText;
    }

    private static Component[] emptyMessages() {
        return new Component[] { Component.empty(), Component.empty(), Component.empty(), Component.empty() };
    }

    public boolean hasGlowingText() {
        return hasGlowingText;
    }

    public SignText setHasGlowingText(boolean glowing) {
        return glowing == hasGlowingText ? this : new SignText(messages, filteredMessages, color, glowing);
    }

    public DyeColor getColor() {
        return color;
    }

    public SignText setColor(DyeColor dye) {
        return dye == color ? this : new SignText(messages, filteredMessages, dye, hasGlowingText);
    }

    public Component getMessage(int line, boolean filtered) {
        return getMessages(filtered)[line];
    }

    public SignText setMessage(int line, Component text) {
        return setMessage(line, text, text);
    }

    public SignText setMessage(int line, Component text, Component filtered) {
        Component[] lines = Arrays.copyOf(messages, messages.length);
        Component[] filteredLines = Arrays.copyOf(filteredMessages, filteredMessages.length);
        lines[line] = text;
        filteredLines[line] = filtered;
        return new SignText(lines, filteredLines, color, hasGlowingText);
    }

    public boolean hasMessage(Player player) {
        return Arrays.stream(getMessages(player.isTextFilteringEnabled())).anyMatch(c -> !c.getString().isEmpty());
    }

    public Component[] getMessages(boolean filtered) {
        return filtered ? filteredMessages : messages;
    }

    public FormattedCharSequence[] getRenderMessages(boolean filtered, Function render) {
        if (renderMessages == null || renderMessagedFiltered != filtered) {
            renderMessagedFiltered = filtered;
            renderMessages = new FormattedCharSequence[LINES];
            for (int i = 0; i < LINES; i++) {
                renderMessages[i] = (FormattedCharSequence) render.apply(getMessage(i, filtered));
            }
        }
        return renderMessages;
    }

    public boolean hasAnyClickCommands(Player player) {
        for (Component c : getMessages(player.isTextFilteringEnabled())) {
            Style style = c.getStyle();
            ClickEvent click = style.getClickEvent();
            if (click != null && click.getAction() == ClickEvent.Action.RUN_COMMAND) {
                return true;
            }
        }
        return false;
    }
}
