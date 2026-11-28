package mmt.standin.net.minecraft.client.renderer.texture;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceMetadata;

/**
 * 1.20.1's SpriteContents constructor on 1.20.2, as the old class mods extend and make.
 *
 * Read from both releases: 1.20.1's SpriteContents(name, frameSize, image, animation) built the sprite's animation from the
 * AnimationMetadataSection it was given. 1.20.2's SpriteContents(name, frameSize, image, metadata) takes the sprite's whole
 * ResourceMetadata, keeps it, and reads the same section from it:
 * metadata.getSection(AnimationMetadataSection.SERIALIZER).orElse(AnimationMetadataSection.EMPTY). So the old constructor
 * is the new one given metadata holding that one section, made with ResourceMetadata.Builder as 1.20.2's own
 * copySections does.
 *
 * NeoForge's primers begin at 1.20.2 -> 1.20.3, and Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html) does
 * not mention SpriteContents; this is from the game's code alone.
 */
public class SpriteContents extends net.minecraft.client.renderer.texture.SpriteContents {

    public SpriteContents(ResourceLocation name, FrameSize frameSize, NativeImage image, AnimationMetadataSection animation) {
        super(name, frameSize, image, metadata(animation));
    }

    public static net.minecraft.client.renderer.texture.SpriteContents init(ResourceLocation name, FrameSize frameSize, NativeImage image, AnimationMetadataSection animation) {
        return new SpriteContents(name, frameSize, image, animation);
    }

    private static ResourceMetadata metadata(AnimationMetadataSection animation) {
        return new ResourceMetadata.Builder().put(AnimationMetadataSection.SERIALIZER, animation).build();
    }
}
