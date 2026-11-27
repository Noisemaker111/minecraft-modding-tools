package mmt.standin.net.minecraft.client.resources;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.20.1's DefaultPlayerSkin getters on 1.20.2.
 *
 * Read from both releases: 1.20.1's DefaultPlayerSkin kept the default skins as (texture, model) pairs and picked one by
 * Math.floorMod(uuid.hashCode(), count): getDefaultSkin(uuid) its texture, getSkinModelName(uuid) its model's id ("slim"
 * or "default"), getDefaultSkin() the seventh one's texture. 1.20.2 keeps the same skins, in the same order, as
 * PlayerSkin records: get(uuid) picks one the same way, texture() and model().id() read it, and getDefaultTexture() is
 * the seventh one's texture.
 *
 * NeoForge's primers begin at 1.20.2 -> 1.20.3, and Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html) does
 * not mention skins; this is from the game's code alone.
 */
public final class DefaultPlayerSkin {

    private DefaultPlayerSkin() {}

    public static ResourceLocation getDefaultSkin() {
        return net.minecraft.client.resources.DefaultPlayerSkin.getDefaultTexture();
    }

    public static ResourceLocation getDefaultSkin(UUID id) {
        return net.minecraft.client.resources.DefaultPlayerSkin.get(id).texture();
    }

    public static String getSkinModelName(UUID id) {
        return net.minecraft.client.resources.DefaultPlayerSkin.get(id).model().id();
    }
}
