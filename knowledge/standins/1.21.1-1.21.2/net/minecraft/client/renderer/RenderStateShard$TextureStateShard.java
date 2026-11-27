package mmt.standin.net.minecraft.client.renderer;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.TriState;

/**
 * 1.21.1's new RenderStateShard.TextureStateShard(texture, blur, mipmap) on 1.21.2.
 *
 * Read from both releases: 1.21.1's shard set the texture's filter to (blur, mipmap) and bound it. 1.21.2's takes the blur
 * as a TriState and sets the filter to blur.toBoolean(texture's default blur), so TRUE and FALSE are the old true and false
 * whatever the default; the game's own shards write TriState.FALSE where 1.21.1 wrote false.
 *
 * NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0) adds "TriState - An enum that represents three possible
 * states: true, false, or default" and does not name this constructor; the mapping of each boolean is from the game's code.
 */
public final class RenderStateShard$TextureStateShard {

    private RenderStateShard$TextureStateShard() {}

    public static RenderStateShard.TextureStateShard init(ResourceLocation texture, boolean blur, boolean mipmap) {
        return new RenderStateShard.TextureStateShard(texture, blur ? TriState.TRUE : TriState.FALSE, mipmap);
    }
}
