package mmt.standin.com.mojang.blaze3d.vertex;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 1.21's VertexConsumer on 1.20.6, for a port back: a vertex begun with addVertex and given its parts with set*, in any
 * order, ending by itself.
 *
 * Read from both releases: 1.21's BufferBuilder.addVertex ends the vertex before it (endLastVertex, which throws
 * "Missing elements in vertex" when one the format has was not set) and each setter writes its part wherever the format
 * puts it, ignoring a part the format lacks (beginElement returns -1). 1.20.6's VertexConsumer has the same parts as
 * vertex(double, double, double), color(int, int, int, int), uv(float, float), overlayCoords(int, int), uv2(int, int)
 * and normal(float, float, float), but its BufferBuilder (BufferVertexConsumer) writes a part only when it is the
 * format's current element, ignoring it otherwise, and a vertex ends only on endVertex(), which throws "Not filled all
 * elements of the vertex" before the last element. So the parts a mod sets are kept until they can be written: on a
 * BufferBuilder in the format's own order, read from currentElement() (back on the position when the vertex is full),
 * and the vertex ended as soon as it is; on any other consumer (1.20.6's VertexMultiConsumer, SheetedDecalTextureGenerator,
 * OutlineBufferSource's generator: they keep or forward each part and write on endVertex()) when it has as many parts as
 * the consumer's vertex before, else when the next vertex begins. They are written there in an order every 1.20.6
 * format's (DefaultVertexFormat) is a subsequence of, so a forwarding consumer's builders each take theirs.
 * 1.21's addVertex(x, y, z, color, u, v, overlay, light, nx, ny, nz) is the whole vertex at once; its color is ARGB
 * (ARGB32), 1.20.6's color(int, int, int, int) takes red, green, blue, alpha. setLight and setOverlay are 1.21's
 * setUv2 and setUv1 of the packed value's two halves, as 1.20.6's uv2(int) and overlayCoords(int) are.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`#addVertex` must always be called first, followed by the settings specified in the `VertexFormat`. `#endVertex` no longer exists
 * and is called automatically when calling `#addVertex` or when uploading the buffer." Its list: "`vertex` -> `addVertex`",
 * "`color` -> `setColor`", "`uv` -> `setUv`", "`overlayCoords` -> `setUv1`, `setOverlay`", "`uv2` -> `setUv2`, `setLight`",
 * "`normal` -> `setNormal`", "`endVertex` is removed".
 */
public final class VertexConsumer {

    private VertexConsumer() {}

    static final int POS = 1;
    static final int COLOR = 2;
    static final int UV0 = 4;
    static final int UV1 = 8;
    static final int UV2 = 16;
    static final int NORMAL = 32;

    /** The parts of the vertex a consumer is being given, kept until they are written. */
    static final class Open {
        float x;
        float y;
        float z;
        int r;
        int g;
        int b;
        int a;
        float u;
        float v;
        int u1;
        int v1;
        int u2;
        int v2;
        float nx;
        float ny;
        float nz;
        int set;
        int written;
        int parts = -1;
    }

    static final Map<com.mojang.blaze3d.vertex.VertexConsumer, Open> OPEN = Collections.synchronizedMap(new WeakHashMap<>());

    static Open open(com.mojang.blaze3d.vertex.VertexConsumer self) {
        Open o = OPEN.get(self);
        if (o == null) {
            o = new Open();
            OPEN.put(self, o);
        }
        return o;
    }

    /** Ends the vertex a consumer was given, written whole or with what it has: 1.21 ends it when the next begins or the buffer is built. */
    static void endOpen(com.mojang.blaze3d.vertex.VertexConsumer self) {
        Open o = OPEN.get(self);
        if (o == null || o.set == 0) {
            return;
        }
        if (self instanceof BufferBuilder builder) {
            drain(builder, o);
        } else {
            o.parts = Integer.bitCount(o.set);
            writeAll(self, o);
            self.endVertex();
        }
        o.set = 0;
        o.written = 0;
    }

    /** Called by the stand-in BufferBuilder before its batch is built or dropped: what it still holds belongs to no vertex. */
    static void forget(com.mojang.blaze3d.vertex.VertexConsumer self) {
        OPEN.remove(self);
    }

    static com.mojang.blaze3d.vertex.VertexConsumer begin(com.mojang.blaze3d.vertex.VertexConsumer self, float x, float y, float z) {
        endOpen(self);
        Open o = open(self);
        o.x = x;
        o.y = y;
        o.z = z;
        o.set = POS;
        o.written = 0;
        return wrote(self, o);
    }

    /** After a part is set: written as far as the consumer can take it, and the vertex ended when full. */
    static com.mojang.blaze3d.vertex.VertexConsumer wrote(com.mojang.blaze3d.vertex.VertexConsumer self, Open o) {
        if (self instanceof BufferBuilder builder) {
            drain(builder, o);
        } else if (o.parts > 0 && Integer.bitCount(o.set) >= o.parts) {
            writeAll(self, o);
            self.endVertex();
            o.set = 0;
        }
        return self;
    }

    /** Writes a builder's current elements while the vertex has them, and ends the vertex once the format is back on its position. */
    static void drain(BufferBuilder builder, Open o) {
        if (!builder.building()) {
            return;
        }
        while (true) {
            VertexFormatElement e = builder.currentElement();
            int part = part(e);
            if (part == 0 || (o.set & part) == 0 || (o.written & part) != 0) {
                break;
            }
            write(builder, o, part);
            o.written |= part;
            if (builder.currentElement().getUsage() == VertexFormatElement.Usage.POSITION) {
                builder.endVertex();
                o.set = 0;
                o.written = 0;
                return;
            }
        }
    }

    static int part(VertexFormatElement e) {
        VertexFormatElement.Usage usage = e.getUsage();
        if (usage == VertexFormatElement.Usage.POSITION) {
            return POS;
        }
        if (usage == VertexFormatElement.Usage.COLOR) {
            return COLOR;
        }
        if (usage == VertexFormatElement.Usage.NORMAL) {
            return NORMAL;
        }
        if (usage == VertexFormatElement.Usage.UV) {
            return e.getIndex() == 0 ? UV0 : e.getIndex() == 1 ? UV1 : e.getIndex() == 2 ? UV2 : 0;
        }
        return 0;
    }

    static void write(com.mojang.blaze3d.vertex.VertexConsumer c, Open o, int part) {
        switch (part) {
            case POS -> c.vertex(o.x, o.y, o.z);
            case COLOR -> c.color(o.r, o.g, o.b, o.a);
            case UV0 -> c.uv(o.u, o.v);
            case UV1 -> c.overlayCoords(o.u1, o.v1);
            case UV2 -> c.uv2(o.u2, o.v2);
            case NORMAL -> c.normal(o.nx, o.ny, o.nz);
            default -> {
            }
        }
    }

    /** Every 1.20.6 format's element order is a subsequence of this one; an in-order builder ignores the parts not current. */
    static final int[] ORDER = {POS, UV0, UV2, COLOR, UV0, UV1, UV2, NORMAL, COLOR};

    static void writeAll(com.mojang.blaze3d.vertex.VertexConsumer c, Open o) {
        for (int part : ORDER) {
            if ((o.set & part) != 0) {
                write(c, o, part);
            }
        }
    }

    public static com.mojang.blaze3d.vertex.VertexConsumer addVertex(com.mojang.blaze3d.vertex.VertexConsumer self, float x, float y, float z) {
        return begin(self, x, y, z);
    }

    public static com.mojang.blaze3d.vertex.VertexConsumer addVertex(com.mojang.blaze3d.vertex.VertexConsumer self, Vector3f p) {
        return begin(self, p.x(), p.y(), p.z());
    }

    public static com.mojang.blaze3d.vertex.VertexConsumer addVertex(com.mojang.blaze3d.vertex.VertexConsumer self, PoseStack.Pose pose, Vector3f p) {
        return addVertex(self, pose.pose(), p.x(), p.y(), p.z());
    }

    public static com.mojang.blaze3d.vertex.VertexConsumer addVertex(com.mojang.blaze3d.vertex.VertexConsumer self, PoseStack.Pose pose, float x, float y, float z) {
        return addVertex(self, pose.pose(), x, y, z);
    }

    public static com.mojang.blaze3d.vertex.VertexConsumer addVertex(com.mojang.blaze3d.vertex.VertexConsumer self, Matrix4f m, float x, float y, float z) {
        Vector3f p = m.transformPosition(x, y, z, new Vector3f());
        return begin(self, p.x(), p.y(), p.z());
    }

    public static void addVertex(com.mojang.blaze3d.vertex.VertexConsumer self, float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
        begin(self, x, y, z);
        Open o = open(self);
        o.r = color >> 16 & 255;
        o.g = color >> 8 & 255;
        o.b = color & 255;
        o.a = color >>> 24;
        o.u = u;
        o.v = v;
        o.u1 = overlay & 65535;
        o.v1 = overlay >> 16 & 65535;
        o.u2 = light & 65535;
        o.v2 = light >> 16 & 65535;
        o.nx = nx;
        o.ny = ny;
        o.nz = nz;
        o.set = POS | COLOR | UV0 | UV1 | UV2 | NORMAL;
        if (self instanceof BufferBuilder) {
            wrote(self, o);
        } else {
            endOpen(self);
        }
    }

    public static com.mojang.blaze3d.vertex.VertexConsumer setColor(com.mojang.blaze3d.vertex.VertexConsumer self, int r, int g, int b, int a) {
        Open o = open(self);
        o.r = r;
        o.g = g;
        o.b = b;
        o.a = a;
        o.set |= COLOR;
        return wrote(self, o);
    }

    public static com.mojang.blaze3d.vertex.VertexConsumer setColor(com.mojang.blaze3d.vertex.VertexConsumer self, float r, float g, float b, float a) {
        return setColor(self, (int) (r * 255.0F), (int) (g * 255.0F), (int) (b * 255.0F), (int) (a * 255.0F));
    }

    public static com.mojang.blaze3d.vertex.VertexConsumer setColor(com.mojang.blaze3d.vertex.VertexConsumer self, int argb) {
        return setColor(self, argb >> 16 & 255, argb >> 8 & 255, argb & 255, argb >>> 24);
    }

    public static com.mojang.blaze3d.vertex.VertexConsumer setWhiteAlpha(com.mojang.blaze3d.vertex.VertexConsumer self, int alpha) {
        return setColor(self, 255, 255, 255, alpha);
    }

    public static com.mojang.blaze3d.vertex.VertexConsumer setUv(com.mojang.blaze3d.vertex.VertexConsumer self, float u, float v) {
        Open o = open(self);
        o.u = u;
        o.v = v;
        o.set |= UV0;
        return wrote(self, o);
    }

    public static com.mojang.blaze3d.vertex.VertexConsumer setUv1(com.mojang.blaze3d.vertex.VertexConsumer self, int u, int v) {
        Open o = open(self);
        o.u1 = u;
        o.v1 = v;
        o.set |= UV1;
        return wrote(self, o);
    }

    public static com.mojang.blaze3d.vertex.VertexConsumer setOverlay(com.mojang.blaze3d.vertex.VertexConsumer self, int overlay) {
        return setUv1(self, overlay & 65535, overlay >> 16 & 65535);
    }

    public static com.mojang.blaze3d.vertex.VertexConsumer setUv2(com.mojang.blaze3d.vertex.VertexConsumer self, int u, int v) {
        Open o = open(self);
        o.u2 = u;
        o.v2 = v;
        o.set |= UV2;
        return wrote(self, o);
    }

    public static com.mojang.blaze3d.vertex.VertexConsumer setLight(com.mojang.blaze3d.vertex.VertexConsumer self, int light) {
        return setUv2(self, light & 65535, light >> 16 & 65535);
    }

    public static com.mojang.blaze3d.vertex.VertexConsumer setNormal(com.mojang.blaze3d.vertex.VertexConsumer self, float x, float y, float z) {
        Open o = open(self);
        o.nx = x;
        o.ny = y;
        o.nz = z;
        o.set |= NORMAL;
        return wrote(self, o);
    }

    public static com.mojang.blaze3d.vertex.VertexConsumer setNormal(com.mojang.blaze3d.vertex.VertexConsumer self, PoseStack.Pose pose, float x, float y, float z) {
        Vector3f n = pose.transformNormal(x, y, z, new Vector3f());
        return setNormal(self, n.x(), n.y(), n.z());
    }
}
