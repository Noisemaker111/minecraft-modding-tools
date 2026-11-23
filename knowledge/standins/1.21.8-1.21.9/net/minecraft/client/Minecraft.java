package mmt.standin.net.minecraft.client;

import com.mojang.authlib.minecraft.MinecraftSessionService;
import java.util.Locale;
import java.util.function.Function;
import net.minecraft.ChatFormatting;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.21.8's Minecraft.getTextureAtlas, getMinecraftSessionService and fpsString on 1.21.9.
 *
 * Read from both releases: 1.21.8's getTextureAtlas(atlas) answered the sprite lookup of an atlas the model manager held
 * (getAtlas(atlas)::getSprite); 1.21.9 keeps every atlas in the AtlasManager (getAtlasManager().getAtlasOrThrow(atlas),
 * the same TextureAtlas with the same getSprite). getMinecraftSessionService() answered the authlib session service
 * 1.21.9 hands out through services().sessionService(). fpsString was rewritten once a second as "%d fps T: %s%s%s%s B: %d%s"
 * from the frames of the last second, the frame limit ("inf" at 260), " vsync " or " ", the graphics mode, the cloud
 * setting (none, " fast-clouds" or " fancy-clouds"), the biome blend radius and the GPU share (" GPU: n%", red "100%" over
 * 100, nothing at 0); 1.21.9 still counts the same frames (getFps()), keeps the limit, the GPU share and every option it
 * read, and dropped only the string, so it is written from them when read.
 *
 * Agrees with NeoForge's 1.21.8 -> 1.21.9 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.9/index.md, CC BY 4.0):
 * "getTextureAtlas -> AtlasManager#getAtlasOrThrow, not one-to-one", "getMinecraftSessionService -> services, now returning a
 * Service instance", "fpsString, sectionPath, sectionVisibility are removed".
 */
public final class Minecraft {

    private Minecraft() {}

    public static Function<ResourceLocation, TextureAtlasSprite> getTextureAtlas(net.minecraft.client.Minecraft self, ResourceLocation atlas) {
        TextureAtlas textures = self.getAtlasManager().getAtlasOrThrow(atlas);
        return location -> textures.getSprite(location);
    }

    public static MinecraftSessionService getMinecraftSessionService(net.minecraft.client.Minecraft self) {
        return self.services().sessionService();
    }

    public static String fpsString(net.minecraft.client.Minecraft self) {
        double gpu = self.getGpuUtilization();
        String gpuText = gpu > 0.0 ? " GPU: " + (gpu > 100.0 ? ChatFormatting.RED + "100%" : Math.round(gpu) + "%") : "";
        int limit = self.getFramerateLimitTracker().getFramerateLimit();
        Object clouds = self.options.cloudStatus().get();
        return String.format(
            Locale.ROOT,
            "%d fps T: %s%s%s%s B: %d%s",
            self.getFps(),
            limit == 260 ? "inf" : limit,
            Boolean.TRUE.equals(self.options.enableVsync().get()) ? " vsync " : " ",
            self.options.graphicsMode().get(),
            clouds == CloudStatus.OFF ? "" : (clouds == CloudStatus.FAST ? " fast-clouds" : " fancy-clouds"),
            self.options.biomeBlendRadius().get(),
            gpuText);
    }
}
