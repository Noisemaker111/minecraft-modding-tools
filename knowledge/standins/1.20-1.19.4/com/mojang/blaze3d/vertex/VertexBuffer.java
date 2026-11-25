package mmt.standin.com.mojang.blaze3d.vertex;

/**
 * 1.20's VertexBuffer(Usage) and VertexBuffer.Usage on 1.19.4.
 *
 * Read from both releases: 1.20's constructor takes a Usage (STATIC, GL id 35044; DYNAMIC, 35048) that upload passes to
 * glBufferData as the buffer's usage hint; 1.19.4's VertexBuffer() uploads every buffer with 35044 (GL_STATIC_DRAW).
 * The hint tells the driver how often the data changes and changes nothing drawn, so a 1.19.4 VertexBuffer is made for
 * either, and a DYNAMIC buffer is uploaded as 1.19.4 uploads all of them.
 */
public final class VertexBuffer {

    private VertexBuffer() {
    }

    public static com.mojang.blaze3d.vertex.VertexBuffer init(Usage usage) {
        return new com.mojang.blaze3d.vertex.VertexBuffer();
    }

    public enum Usage {
        STATIC(35044),
        DYNAMIC(35048);

        final int id;

        Usage(int id) {
            this.id = id;
        }
    }
}
