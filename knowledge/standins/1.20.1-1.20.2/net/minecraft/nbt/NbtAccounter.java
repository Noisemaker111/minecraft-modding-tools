package mmt.standin.net.minecraft.nbt;

/**
 * 1.20.1's NbtAccounter constructor on 1.20.2.
 *
 * Read from both releases: 1.20.1's NbtAccounter(quota) counted the bytes a read used against quota. 1.20.2's counts depth
 * too, NbtAccounter(quota, maxDepth), and NbtAccounter.create(quota) is the one with the depth the game uses everywhere
 * (512), as FriendlyByteBuf.readNbt() makes its 2 MiB accounter.
 *
 * NeoForge's primers begin at 1.20.2 -> 1.20.3, and Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html) does
 * not mention NbtAccounter; this is from the game's code alone.
 */
public final class NbtAccounter {

    private NbtAccounter() {}

    public static net.minecraft.nbt.NbtAccounter init(long quota) {
        return net.minecraft.nbt.NbtAccounter.create(quota);
    }
}
