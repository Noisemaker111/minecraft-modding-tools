package mmt.standin.net.minecraft.network;

import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

/**
 * 1.20.4's FriendlyByteBuf.readComponent() and writeComponent(component) on 1.20.5.
 *
 * Read from both releases: 1.20.4's writeComponent wrote the component as NBT through ComponentSerialization.CODEC with
 * NbtOps (writeWithCodec), and readComponent read it back the same way with a 2 MiB NBT quota
 * (readWithCodec(NbtOps.INSTANCE, CODEC, NbtAccounter.create(2097152))). 1.20.5 moved components to stream codecs that
 * need a RegistryFriendlyByteBuf (ComponentSerialization.STREAM_CODEC, NBT through the registries' ops), and dropped the
 * two methods; FriendlyByteBuf keeps readWithCodec and writeWithCodec, and ComponentSerialization.CODEC is still the
 * component codec. So the old methods write and read the same bytes as before, which a mod's own packets need on both
 * ends.
 *
 * Agrees with NeoForge's 1.20.4 -> 1.20.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.20.5/index.md, CC BY 4.0): "readComponent, readComponentTrusted, writeComponent -> ComponentSerialization#STREAM_CODEC, TRUSTED_STREAM_CODEC".
 */
public final class FriendlyByteBuf {

    private FriendlyByteBuf() {}

    public static Component readComponent(net.minecraft.network.FriendlyByteBuf self) {
        return (Component) self.readWithCodec(NbtOps.INSTANCE, ComponentSerialization.CODEC, NbtAccounter.create(2097152L));
    }

    public static net.minecraft.network.FriendlyByteBuf writeComponent(net.minecraft.network.FriendlyByteBuf self, Component component) {
        return self.writeWithCodec(NbtOps.INSTANCE, ComponentSerialization.CODEC, component);
    }
}
