package mmt.standin.net.minecraft.network.chat;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import com.google.gson.JsonElement;

/**
 * 1.20.4's Component.Serializer on 1.20.5.
 *
 * Read from both releases: 1.20.4's fromJson(json), for a string or a JsonElement, parsed the string and decoded it as a component (null for a null
 * element), needing no registries: the items a hover event named were read by id from the built-in item registry.
 * 1.20.5's fromJson(json, registries) (both forms) does the same with registries for the parts that name registry entries. The
 * built-in registries (RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)) are the ones 1.20.4 read.
 *
 * NeoForge's 1.20.4 -> 1.20.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.20.5/index.md, CC BY 4.0) does not list this method; it is from the game's code alone.
 */
public final class Component$Serializer {

    private Component$Serializer() {}

    public static MutableComponent fromJson(String json) {
        return Component.Serializer.fromJson(json, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }

    public static MutableComponent fromJson(JsonElement json) {
        return Component.Serializer.fromJson(json, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }
}
