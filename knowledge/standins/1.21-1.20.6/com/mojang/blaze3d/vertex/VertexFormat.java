package mmt.standin.com.mojang.blaze3d.vertex;

/**
 * 1.21's VertexFormat members 1.20.6 lacks, for a port back: builder(), getElements() as a List, getOffset and contains.
 *
 * Read from both releases: 1.21's VertexFormat is made with builder().add(name, element)...padding(bytes).build() and
 * keeps each element's byte offset (getOffset, -1 when the format has none of it, UNKNOWN_ELEMENT); 1.20.6's is made
 * from an ImmutableMap of names to elements, in order, each element starting where the one before ended, and padding is
 * an element of its own (Usage.PADDING). getElements() is the same list in both (1.20.6 types it ImmutableList).
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`com.mojang.blaze3d.vertex.DefaultVertexFormat`'s `VertexFormatElement`s have been moved to `VertexFormatElement`."
 */
public final class VertexFormat {

    private VertexFormat() {}

    public static VertexFormat$Builder builder() {
        return new VertexFormat$Builder();
    }

    public static java.util.List getElements(com.mojang.blaze3d.vertex.VertexFormat self) {
        return self.getElements();
    }

    public static int getOffset(com.mojang.blaze3d.vertex.VertexFormat self, com.mojang.blaze3d.vertex.VertexFormatElement element) {
        int offset = 0;
        for (Object o : self.getElements()) {
            com.mojang.blaze3d.vertex.VertexFormatElement e = (com.mojang.blaze3d.vertex.VertexFormatElement) o;
            if (e.equals(element)) {
                return offset;
            }
            offset += e.getByteSize();
        }
        return -1;
    }

    public static boolean contains(com.mojang.blaze3d.vertex.VertexFormat self, com.mojang.blaze3d.vertex.VertexFormatElement element) {
        return self.getElements().contains(element);
    }

    public static int getElementsMask(com.mojang.blaze3d.vertex.VertexFormat self) {
        int mask = 0;
        for (Object o : self.getElements()) {
            mask |= VertexFormatElement.mask((com.mojang.blaze3d.vertex.VertexFormatElement) o);
        }
        return mask;
    }
}
