package mmt.standin.net.minecraft.client.renderer.texture;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.21.3's TextureManager on 1.21.4.
 *
 * Read from both releases: 1.21.3's register(prefix, texture) counted the textures registered under each prefix
 * (prefixRegister, from 1), named the texture minecraft:dynamic/PREFIX_N and registered it under that id with
 * register(id, texture), returning the id. 1.21.4 dropped the prefix form and its counter; register(id, texture) is
 * still there. The counter is kept here (the client has one texture manager), the rest is the same.
 *
 * NeoForge's 1.21.3 -> 1.21.4 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.4/index.md, CC BY 4.0) does not list this method; it is from the game's code alone.
 */
public final class TextureManager {

    private static final Map<String, Integer> COUNTS = new HashMap<>();

    private TextureManager() {}

    public static synchronized ResourceLocation register(net.minecraft.client.renderer.texture.TextureManager self, String prefix, DynamicTexture texture) {
        int n = COUNTS.merge(prefix, 1, Integer::sum);
        ResourceLocation id = ResourceLocation.withDefaultNamespace(String.format(Locale.ROOT, "dynamic/%s_%d", prefix, n));
        self.register(id, texture);
        return id;
    }
}
