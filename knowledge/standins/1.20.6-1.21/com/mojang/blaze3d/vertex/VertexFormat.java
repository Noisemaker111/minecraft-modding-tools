package mmt.standin.com.mojang.blaze3d.vertex;

/**
 * 1.20.6's VertexFormat on 1.21.
 *
 * Read from both releases: 1.20.6's getIntegerSize() was getVertexSize() / 4, a vertex's size in 4-byte ints. 1.21 keeps
 * getVertexSize() (bytes) and drops the int size.
 *
 * Game files only: the primer does not list it.
 */
public final class VertexFormat {

    private VertexFormat() {}

    public static int getIntegerSize(com.mojang.blaze3d.vertex.VertexFormat self) {
        return self.getVertexSize() / 4;
    }
}
