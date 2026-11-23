package mmt.standin.net.minecraft.world.item.crafting;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.util.function.Function;
import net.minecraft.Util;

/**
 * 1.20.2's Ingredient.toJson(allowEmpty) on 1.20.3.
 *
 * Read from both releases: 1.20.2's toJson(allowEmpty) encoded the ingredient to JSON with Ingredient.CODEC when empty
 * ingredients were allowed and CODEC_NONEMPTY when not (encodeStart(JsonOps.INSTANCE, this)), throwing an
 * IllegalStateException with the codec's message on failure (Util.getOrThrow). 1.20.3 moved recipe serialization to
 * codecs and dropped the method; both codecs and Util.getOrThrow are unchanged. So the old method makes the same call.
 *
 * NeoForge's primers begin at 1.20.4 -> 1.20.5; this is from the game's code alone.
 */
public final class Ingredient {

    private Ingredient() {}

    public static JsonElement toJson(net.minecraft.world.item.crafting.Ingredient self, boolean allowEmpty) {
        Codec codec = allowEmpty ? net.minecraft.world.item.crafting.Ingredient.CODEC : net.minecraft.world.item.crafting.Ingredient.CODEC_NONEMPTY;
        return (JsonElement) Util.getOrThrow(codec.encodeStart(JsonOps.INSTANCE, self), (Function) message -> new IllegalStateException((String) message));
    }
}
