package mmt.standin.net.minecraft.tags;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.Tag;

/**
 * 1.15.2's FluidTags.Wrapper, which 1.16 dropped.
 *
 * Read from both releases: 1.15.2's new FluidTags.Wrapper(id) was a fluid tag by name that looked the tag up in the
 * loaded fluid tags whenever it was asked (after each reload), empty when there was none: contains(fluid), getValues(),
 * getId(). 1.16 binds named tags through a private StaticTagHelper (FluidTags.bind, private) whose wrappers look the tag
 * up in FluidTags.getAllTags() the same way, getTagOrEmpty(id). This is such a tag: it asks getAllTags() on each call,
 * and is a Tag.Named, so it goes wherever 1.16 takes a tag.
 *
 * Fabric's 1.16 post (https://fabricmc.net/2020/06/23/116.html) does not mention it; this is from the game's code alone.
 */
public class FluidTags$Wrapper implements Tag.Named {

    private final ResourceLocation id;

    public FluidTags$Wrapper(ResourceLocation id) {
        this.id = id;
    }

    Tag actual() {
        return FluidTags.getAllTags().getTagOrEmpty(id);
    }

    public boolean contains(Object fluid) {
        return actual().contains(fluid);
    }

    public List getValues() {
        return actual().getValues();
    }

    public ResourceLocation getName() {
        return id;
    }

    public ResourceLocation getId() {
        return id;
    }
}
