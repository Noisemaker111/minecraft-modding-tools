package mmt.standin.com.mojang.blaze3d.vertex;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;

/**
 * 1.21's VertexFormatElement constants and ids on 1.20.6, for a port back: the same elements, which 1.20.6 keeps in
 * DefaultVertexFormat.
 *
 * Read from both releases: 1.21's VertexFormatElement is a record registered under an id (POSITION 0, COLOR 1, UV0 2,
 * UV1 3, UV2 4, NORMAL 5; UV is UV0), mask() 1 << id; 1.20.6's DefaultVertexFormat.ELEMENT_POSITION, ELEMENT_COLOR,
 * ELEMENT_UV0, ELEMENT_UV1, ELEMENT_UV2 and ELEMENT_NORMAL are the same index, type, usage and count, compared by those
 * (VertexFormatElement.equals), so an element's id is read from which of them it equals. register(id, index, type,
 * usage, count) makes the element 1.20.6's way (index, type, usage, count).
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`com.mojang.blaze3d.vertex.DefaultVertexFormat`'s `VertexFormatElement`s have been moved to `VertexFormatElement`."
 */
public final class VertexFormatElement {

    private VertexFormatElement() {}

    static final com.mojang.blaze3d.vertex.VertexFormatElement[] BY_ID = {
        DefaultVertexFormat.ELEMENT_POSITION, DefaultVertexFormat.ELEMENT_COLOR, DefaultVertexFormat.ELEMENT_UV0,
        DefaultVertexFormat.ELEMENT_UV1, DefaultVertexFormat.ELEMENT_UV2, DefaultVertexFormat.ELEMENT_NORMAL
    };

    public static com.mojang.blaze3d.vertex.VertexFormatElement POSITION() {
        return DefaultVertexFormat.ELEMENT_POSITION;
    }

    public static com.mojang.blaze3d.vertex.VertexFormatElement COLOR() {
        return DefaultVertexFormat.ELEMENT_COLOR;
    }

    public static com.mojang.blaze3d.vertex.VertexFormatElement UV0() {
        return DefaultVertexFormat.ELEMENT_UV0;
    }

    public static com.mojang.blaze3d.vertex.VertexFormatElement UV() {
        return DefaultVertexFormat.ELEMENT_UV0;
    }

    public static com.mojang.blaze3d.vertex.VertexFormatElement UV1() {
        return DefaultVertexFormat.ELEMENT_UV1;
    }

    public static com.mojang.blaze3d.vertex.VertexFormatElement UV2() {
        return DefaultVertexFormat.ELEMENT_UV2;
    }

    public static com.mojang.blaze3d.vertex.VertexFormatElement NORMAL() {
        return DefaultVertexFormat.ELEMENT_NORMAL;
    }

    public static com.mojang.blaze3d.vertex.VertexFormatElement byId(int id) {
        return id >= 0 && id < BY_ID.length ? BY_ID[id] : null;
    }

    public static int id(com.mojang.blaze3d.vertex.VertexFormatElement self) {
        for (int i = 0; i < BY_ID.length; i++) {
            if (BY_ID[i].equals(self)) {
                return i;
            }
        }
        return -1;
    }

    public static int mask(com.mojang.blaze3d.vertex.VertexFormatElement self) {
        int id = id(self);
        return id < 0 ? 0 : 1 << id;
    }

    public static int byteSize(com.mojang.blaze3d.vertex.VertexFormatElement self) {
        return self.getByteSize();
    }

    public static com.mojang.blaze3d.vertex.VertexFormatElement register(int id, int index, com.mojang.blaze3d.vertex.VertexFormatElement.Type type, com.mojang.blaze3d.vertex.VertexFormatElement.Usage usage, int count) {
        com.mojang.blaze3d.vertex.VertexFormatElement known = byId(id);
        com.mojang.blaze3d.vertex.VertexFormatElement made = new com.mojang.blaze3d.vertex.VertexFormatElement(index, type, usage, count);
        return known != null && known.equals(made) ? known : made;
    }
}
