package mmt.standin.net.minecraft.client.resources;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.hash.Hashing;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.properties.Property;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.20.1's SkinManager lookups on 1.20.2.
 *
 * Read from both releases: 1.20.1's getInsecureSkinInformation(profile) decoded the profile's "textures" property without
 * checking its signature (the session service's getTextures(profile, false), cached by the property's value), an empty
 * map where it had none. registerTexture(texture, type) registered an HttpTexture for the texture at
 * TYPE_DIR/sha1(hash) ("skins", "capes" or "elytra") and returned that location at once, the image arriving later;
 * getInsecureSkinLocation(profile) was the skin's location so, else the default skin for the profile's id
 * (UUIDUtil.getOrCreatePlayerUUID, its id or one made from its name; 1.20.2's profiles always have an id, and
 * DefaultPlayerSkin.get(profile) picks by it). 1.20.2 keeps one TextureCache per type (skinTextures, capeTextures, elytraTextures),
 * whose getOrLoad(texture) registers the same HttpTexture at the same location once per hash and completes when it has
 * arrived, and the session service still decodes textures with getTextures(profile, secure). So the old calls are the
 * same decoding, and the cache's registration with its location returned at once.
 *
 * NeoForge's primers begin at 1.20.2 -> 1.20.3, and Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html) does
 * not mention skins; this is from the game's code alone.
 */
public final class SkinManager {

    private SkinManager() {}

    public static Map getInsecureSkinInformation(net.minecraft.client.resources.SkinManager self, GameProfile profile) {
        Property textures = (Property) Iterables.getFirst(profile.getProperties().get("textures"), null);
        if (textures == null) {
            return ImmutableMap.of();
        }
        return Minecraft.getInstance().getMinecraftSessionService().getTextures(profile, false);
    }

    public static ResourceLocation registerTexture(net.minecraft.client.resources.SkinManager self, MinecraftProfileTexture texture, MinecraftProfileTexture.Type type) {
        String folder;
        if (type == MinecraftProfileTexture.Type.SKIN) {
            self.skinTextures.getOrLoad(texture);
            folder = "skins";
        } else if (type == MinecraftProfileTexture.Type.CAPE) {
            self.capeTextures.getOrLoad(texture);
            folder = "capes";
        } else {
            self.elytraTextures.getOrLoad(texture);
            folder = "elytra";
        }
        return new ResourceLocation(folder + "/" + Hashing.sha1().hashUnencodedChars(texture.getHash()).toString());
    }

    public static ResourceLocation getInsecureSkinLocation(net.minecraft.client.resources.SkinManager self, GameProfile profile) {
        MinecraftProfileTexture skin = (MinecraftProfileTexture) getInsecureSkinInformation(self, profile).get(MinecraftProfileTexture.Type.SKIN);
        if (skin != null) {
            return registerTexture(self, skin, MinecraftProfileTexture.Type.SKIN);
        }
        return net.minecraft.client.resources.DefaultPlayerSkin.get(profile).texture();
    }

    public static net.minecraft.client.renderer.texture.TextureManager textureManager(net.minecraft.client.resources.SkinManager self) {
        return Minecraft.getInstance().getTextureManager();
    }
}
