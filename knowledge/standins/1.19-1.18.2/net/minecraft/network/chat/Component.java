package mmt.standin.net.minecraft.network.chat;

import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;

/**
 * 1.19's Component.literal, translatable and empty on 1.18.2, for a port back.
 *
 * Read from both releases: 1.19 makes every component one MutableComponent of its contents: literal(text) is
 * MutableComponent.create(new LiteralContents(text)), translatable(key, args...) the same of new
 * TranslatableContents(key, args...), and empty() of ComponentContents.EMPTY, a component that shows nothing. 1.18.2
 * makes each kind its own class, all of them MutableComponents (BaseComponent): new TextComponent(text) and new
 * TranslatableComponent(key, args...), which show the same text and translation, and new TextComponent("") shows nothing.
 *
 * The Fabric 1.19 post was not at hand to quote and NeoForge's primers begin at 1.20.4 -> 1.20.5; this is from the
 * game's code alone.
 */
public final class Component {

    private Component() {}

    public static MutableComponent literal(String text) {
        return new TextComponent(text);
    }

    public static MutableComponent translatable(String key) {
        return new TranslatableComponent(key);
    }

    public static MutableComponent translatable(String key, Object[] args) {
        return new TranslatableComponent(key, args);
    }

    public static MutableComponent empty() {
        return new TextComponent("");
    }
}
