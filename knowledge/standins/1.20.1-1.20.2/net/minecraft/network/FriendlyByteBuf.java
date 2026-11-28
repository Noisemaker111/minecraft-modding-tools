package mmt.standin.net.minecraft.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.Tag;

/**
 * 1.20.1's FriendlyByteBuf.readNbt(NbtAccounter) and readAnySizeNbt on 1.20.2.
 *
 * Read from both releases: 1.20.1's readNbt(accounter) read a compound tag, or null for the empty tag, within the
 * accounter's quota; readAnySizeNbt() was readNbt(NbtAccounter.UNLIMITED). 1.20.2's readNbt(accounter) reads any tag (null
 * for the empty one), and its own readNbt() turns that back into a compound, failing with a DecoderException on another
 * tag; the unlimited accounter is NbtAccounter.unlimitedHeap(). So each old method is the new read made a compound the way
 * 1.20.2's readNbt() does.
 *
 * NeoForge's primers begin at 1.20.2 -> 1.20.3, and Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html) does
 * not mention readNbt; this is from the game's code alone.
 */
public final class FriendlyByteBuf {

    private FriendlyByteBuf() {}

    public static CompoundTag readNbt(net.minecraft.network.FriendlyByteBuf self, NbtAccounter accounter) {
        Tag tag = self.readNbt(accounter);
        if (tag != null && !(tag instanceof CompoundTag)) {
            throw new DecoderException("Not a compound tag: " + tag);
        }
        return (CompoundTag) tag;
    }

    public static CompoundTag readAnySizeNbt(net.minecraft.network.FriendlyByteBuf self) {
        return readNbt(self, NbtAccounter.unlimitedHeap());
    }
}
