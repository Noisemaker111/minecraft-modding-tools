package mmt.standin.com.mojang.blaze3d.vertex;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;

/**
 * 1.21's VertexFormatElement constants and ids on 1.20.6, for a port back: the same elements, which 1.20.6 keeps in
 * DefaultVertexFormat.
 *
 * Read from both releases: 1.21's VertexFormatElement is a record registered under an id (POSITION 0, COLOR 1, UV0 2,
 * UV1 3, UV2 4, NORMAL 5; UV is UV0), mask() 1 << id; 1.20.6's DefaultVertexFormat.ELEMENT_POSITION, ELEMENT_COLOR,
 * ELEMENT_UV0, ELEMENT_UV1, ELEMENT_UV2 and ELEMENT_NORMAL are the same index, type, usage and count, compared by those
 * (VertexFormatElement.equals). 1.21 keeps every element in BY_ID (32 slots) under the id register gave it: register
 * refuses an id outside [0; 32) and one already taken, byId reads the slot (null when free, which is how mods find the
 * next free id), mask() is 1 << id and elementsFromMask streams the registered elements in registration order. So
 * does this: seeded with the six built-ins, filled by register, read by byId, and an element's id is the one it was
 * registered under (by identity, as 1.21's record carries its id: 1.20.6's equals compares only index, type, usage and
 * count, which a mod's element may share with a built-in), or for an element made without register, the first
 * registered one it equals.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`com.mojang.blaze3d.vertex.DefaultVertexFormat`'s `VertexFormatElement`s have been moved to `VertexFormatElement`."
 */
public final class VertexFormatElement {

    private VertexFormatElement() {}

    static final int MAX_COUNT = 32;
    static final com.mojang.blaze3d.vertex.VertexFormatElement[] BY_ID = new com.mojang.blaze3d.vertex.VertexFormatElement[MAX_COUNT];
    static final java.util.List ELEMENTS = new java.util.ArrayList(MAX_COUNT);
    static final java.util.Map IDS = new java.util.IdentityHashMap();

    static {
        put(0, DefaultVertexFormat.ELEMENT_POSITION);
        put(1, DefaultVertexFormat.ELEMENT_COLOR);
        put(2, DefaultVertexFormat.ELEMENT_UV0);
        put(3, DefaultVertexFormat.ELEMENT_UV1);
        put(4, DefaultVertexFormat.ELEMENT_UV2);
        put(5, DefaultVertexFormat.ELEMENT_NORMAL);
    }

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

    public static synchronized com.mojang.blaze3d.vertex.VertexFormatElement byId(int id) {
        return BY_ID[id];
    }

    public static synchronized int id(com.mojang.blaze3d.vertex.VertexFormatElement self) {
        Object id = IDS.get(self);
        if (id != null) {
            return (Integer) id;
        }
        for (int i = 0; i < BY_ID.length; i++) {
            if (BY_ID[i] != null && BY_ID[i].equals(self)) {
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

    public static synchronized java.util.stream.Stream elementsFromMask(int mask) {
        java.util.List out = new java.util.ArrayList();
        for (Object e : ELEMENTS) {
            if ((mask & mask((com.mojang.blaze3d.vertex.VertexFormatElement) e)) != 0) {
                out.add(e);
            }
        }
        return out.stream();
    }

    public static synchronized com.mojang.blaze3d.vertex.VertexFormatElement register(int id, int index, com.mojang.blaze3d.vertex.VertexFormatElement.Type type, com.mojang.blaze3d.vertex.VertexFormatElement.Usage usage, int count) {
        if (id < 0 || id >= MAX_COUNT) {
            throw new IllegalArgumentException("Element ID must be in range [0; " + MAX_COUNT + ")");
        }
        com.mojang.blaze3d.vertex.VertexFormatElement made = new com.mojang.blaze3d.vertex.VertexFormatElement(index, type, usage, count);
        if (BY_ID[id] != null) {
            throw new IllegalArgumentException("Duplicate element registration for: " + id);
        }
        put(id, made);
        return made;
    }

    static void put(int id, com.mojang.blaze3d.vertex.VertexFormatElement element) {
        BY_ID[id] = element;
        ELEMENTS.add(element);
        IDS.put(element, id);
    }
}
