package mmt.standin.com.mojang.blaze3d.systems;

import com.mojang.blaze3d.buffers.GpuBuffer;
import java.util.function.Supplier;

/**
 * 1.21.10's GpuDevice on 1.21.11.
 *
 * Read from both releases: 1.21.10's createBuffer(label, usage, size) took the size in bytes as an int; 1.21.11's takes
 * it as a long and is otherwise the same. The same size, widened.
 *
 * Agrees with NeoForge's 1.21.10 -> 1.21.11 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.11/index.md, CC BY 4.0): "createBuffer now uses in a long for the size".
 */
public final class GpuDevice {

    private GpuDevice() {}

    public static GpuBuffer createBuffer(com.mojang.blaze3d.systems.GpuDevice self, Supplier label, int usage, int size) {
        return self.createBuffer(label, usage, (long) size);
    }
}
