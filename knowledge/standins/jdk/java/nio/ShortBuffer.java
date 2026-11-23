package mmt.standin.java.nio;

/**
 * Java 13's absolute bulk methods of ShortBuffer, from the JDK's documented contract: get(index, dst, offset, length)
 * copies length elements from this buffer, starting at index, into dst at offset, and put(index, src, offset, length)
 * copies them the other way, neither moving the position; IndexOutOfBoundsException unless both ranges fit, and put on a
 * read-only buffer throws ReadOnlyBufferException. The forms without offset and length use the whole array. Each returns
 * this buffer.
 */
public final class ShortBuffer {

    private ShortBuffer() {}

    public static java.nio.ShortBuffer get(java.nio.ShortBuffer self, int index, short[] dst, int offset, int length) {
        mmt.standin.java.util.Objects.checkFromIndexSize(index, length, self.limit());
        mmt.standin.java.util.Objects.checkFromIndexSize(offset, length, dst.length);
        for (int i = 0; i < length; i++) {
            dst[offset + i] = self.get(index + i);
        }
        return self;
    }

    public static java.nio.ShortBuffer get(java.nio.ShortBuffer self, int index, short[] dst) {
        return get(self, index, dst, 0, dst.length);
    }

    public static java.nio.ShortBuffer put(java.nio.ShortBuffer self, int index, short[] src, int offset, int length) {
        if (self.isReadOnly()) {
            throw new java.nio.ReadOnlyBufferException();
        }
        mmt.standin.java.util.Objects.checkFromIndexSize(index, length, self.limit());
        mmt.standin.java.util.Objects.checkFromIndexSize(offset, length, src.length);
        for (int i = 0; i < length; i++) {
            self.put(index + i, src[offset + i]);
        }
        return self;
    }

    public static java.nio.ShortBuffer put(java.nio.ShortBuffer self, int index, short[] src) {
        return put(self, index, src, 0, src.length);
    }
}
