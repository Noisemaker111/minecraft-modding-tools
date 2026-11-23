package mmt.standin.net.minecraft.network.chat;

import java.util.ArrayList;
import java.util.Optional;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * 1.18.2's TranslatableComponent on 1.19, for a mod whose use of it the route to TranslatableContents cannot carry.
 *
 * Read from both releases: 1.18.2's TranslatableComponent was a component (BaseComponent, so a MutableComponent) holding a
 * translation key and its arguments; mods make one with new TranslatableComponent(key, args...) and pass it wherever a
 * Component or MutableComponent goes, read getKey() and getArgs(), copy it with plainCopy(), and some extend it. 1.19 split
 * every component into one MutableComponent class and its contents: the key and arguments moved to TranslatableContents,
 * which is no component, and 1.19's own code writes Component.translatable(key, args...), which is
 * MutableComponent.create(new TranslatableContents(key, args...)). The route map follows the class to TranslatableContents,
 * which suits a mod that only reads the key; where a mod passes one as a component the JVM's verifier refuses it, and the
 * port uses this class instead: a MutableComponent with TranslatableContents, as 1.19 makes one, under the old name, so the
 * value goes where a component goes and a mod's subclass still extends it.
 *
 * NeoForge's primers begin at 1.20.2 -> 1.20.3 and the Fabric 1.19 post was not at hand to quote; this is from the game's
 * code alone.
 */
public class TranslatableComponent extends MutableComponent {

    public TranslatableComponent(String key) {
        super(new TranslatableContents(key), new ArrayList(), Style.EMPTY);
    }

    public TranslatableComponent(String key, Object... args) {
        super(new TranslatableContents(key, args), new ArrayList(), Style.EMPTY);
    }

    public String getKey() {
        return ((TranslatableContents) getContents()).getKey();
    }

    public Object[] getArgs() {
        return ((TranslatableContents) getContents()).getArgs();
    }

    public TranslatableComponent plainCopy() {
        return new TranslatableComponent(getKey(), getArgs());
    }

    public Optional visitSelf(FormattedText.StyledContentConsumer consumer, Style style) {
        return getContents().visit(consumer, style);
    }

    public Optional visitSelf(FormattedText.ContentConsumer consumer) {
        return getContents().visit(consumer);
    }
}
