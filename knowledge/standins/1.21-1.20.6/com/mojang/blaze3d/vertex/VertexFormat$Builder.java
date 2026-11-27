package mmt.standin.com.mojang.blaze3d.vertex;

/**
 * 1.21's VertexFormat.Builder on 1.20.6, for a port back: the class itself, which 1.20.6 does not have yet.
 *
 * Read from both releases: 1.21's builder adds named elements in order and padding(bytes) leaves a gap before the next;
 * build() makes the format. 1.20.6 makes a format from the same names and elements in an ImmutableMap, a gap being a
 * padding element of that many bytes (DefaultVertexFormat.ELEMENT_PADDING is one byte: Type.BYTE, Usage.PADDING), named
 * so no two names are the same.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`com.mojang.blaze3d.vertex.DefaultVertexFormat`'s `VertexFormatElement`s have been moved to `VertexFormatElement`."
 */
public final class VertexFormat$Builder {

    private final java.util.LinkedHashMap elements = new java.util.LinkedHashMap();
    private int paddings;

    VertexFormat$Builder() {
    }

    public VertexFormat$Builder add(String name, com.mojang.blaze3d.vertex.VertexFormatElement element) {
        if (elements.containsKey(name)) {
            throw new IllegalArgumentException("Multiple entries with same key: " + name);
        }
        elements.put(name, element);
        return this;
    }

    public VertexFormat$Builder padding(int bytes) {
        if (bytes > 0) {
            elements.put("Padding" + (paddings == 0 ? "" : String.valueOf(paddings)), new com.mojang.blaze3d.vertex.VertexFormatElement(0, com.mojang.blaze3d.vertex.VertexFormatElement.Type.BYTE, com.mojang.blaze3d.vertex.VertexFormatElement.Usage.PADDING, bytes));
            paddings++;
        }
        return this;
    }

    public com.mojang.blaze3d.vertex.VertexFormat build() {
        return new com.mojang.blaze3d.vertex.VertexFormat(com.google.common.collect.ImmutableMap.copyOf(elements));
    }
}
