package mmt.standin.net.minecraft.network.chat;

import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.TextComponent;

/**
 * 1.15.2's Component.append, setStyle, withStyle(ChatFormatting...) and getColoredString() on 1.16.
 *
 * Read from both releases: 1.15.2's Component was mutable: append added a sibling (a String as a TextComponent) and
 * returned the same component, setStyle and withStyle changed its style and returned it. 1.16 moved these to
 * MutableComponent (which every BaseComponent, so every TextComponent and TranslatableComponent, is), with the same
 * effect and result; a component that is not mutable is copied first (copy() is a MutableComponent). getColoredString()
 * wrote each piece of text after the legacy codes of its style (colour, then bold, italic, underline, obfuscated,
 * strikethrough), a reset code before a change of style and at the end; 1.16 dropped it and walks a component's pieces
 * with their resolved styles through visit(StyledContentConsumer, Style). This writes the same string from that walk.
 *
 * Fabric's 1.16 post (https://fabricmc.net/2020/06/23/116.html) does not mention it; this is from the game's code alone.
 */
public final class Component {

    private Component() {}

    static MutableComponent mutable(net.minecraft.network.chat.Component self) {
        return self instanceof MutableComponent ? (MutableComponent) self : self.copy();
    }

    public static net.minecraft.network.chat.Component append(net.minecraft.network.chat.Component self, net.minecraft.network.chat.Component sibling) {
        return mutable(self).append(sibling);
    }

    public static net.minecraft.network.chat.Component append(net.minecraft.network.chat.Component self, String text) {
        return mutable(self).append(new TextComponent(text));
    }

    public static net.minecraft.network.chat.Component setStyle(net.minecraft.network.chat.Component self, Style style) {
        return mutable(self).setStyle(style);
    }

    public static net.minecraft.network.chat.Component withStyle(net.minecraft.network.chat.Component self, ChatFormatting[] formats) {
        return mutable(self).withStyle(formats);
    }

    public static String getColoredString(net.minecraft.network.chat.Component self) {
        final StringBuilder out = new StringBuilder();
        final String[] last = {""};
        self.visit(new FormattedText.StyledContentConsumer() {
            public Optional accept(Style style, String text) {
                if (!text.isEmpty()) {
                    String codes = codes(style);
                    if (!codes.equals(last[0])) {
                        if (!last[0].isEmpty()) {
                            out.append(ChatFormatting.RESET);
                        }
                        out.append(codes);
                        last[0] = codes;
                    }
                    out.append(text);
                }
                return Optional.empty();
            }
        }, Style.EMPTY);
        if (!last[0].isEmpty()) {
            out.append(ChatFormatting.RESET);
        }
        return out.toString();
    }

    static String codes(Style style) {
        StringBuilder codes = new StringBuilder();
        TextColor color = style.getColor();
        ChatFormatting legacy = color == null ? null : ChatFormatting.getByName(color.serialize());
        if (legacy != null) {
            codes.append(legacy);
        }
        if (style.isBold()) {
            codes.append(ChatFormatting.BOLD);
        }
        if (style.isItalic()) {
            codes.append(ChatFormatting.ITALIC);
        }
        if (style.isUnderlined()) {
            codes.append(ChatFormatting.UNDERLINE);
        }
        if (style.isObfuscated()) {
            codes.append(ChatFormatting.OBFUSCATED);
        }
        if (style.isStrikethrough()) {
            codes.append(ChatFormatting.STRIKETHROUGH);
        }
        return codes.toString();
    }
}
