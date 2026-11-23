package mmt.standin.java.nio;

/**
 * Java 13's absolute bulk methods of ByteBuffer, from the JDK's documented contract: get(index, dst, offset, length)
 * copies length elements from this buffer, starting at index, into dst at offset, and put(index, src, offset, length)
 * copies them the other way, neither moving the position; IndexOutOfBoundsException unless both ranges fit, and put on a
 * read-only buffer throws ReadOnlyBufferException. The forms without offset and length use the whole array. Each returns
 * this buffer. slice(index, length) (Java 13): a new buffer sharing this one's content from index, length elements long, as duplicate() limited to the range and sliced.
 */
public final class ByteBuffer {

    private ByteBuffer() {}

    public static java.nio.ByteBuffer get(java.nio.ByteBuffer self, int index, byte[] dst, int offset, int length) {
        mmt.standin.java.util.Objects.checkFromIndexSize(index, length, self.limit());
        mmt.standin.java.util.Objects.checkFromIndexSize(offset, length, dst.length);
        for (int i = 0; i < length; i++) {
            dst[offset + i] = self.get(index + i);
        }
        return self;
    }

    public static java.nio.ByteBuffer get(java.nio.ByteBuffer self, int index, byte[] dst) {
        return get(self, index, dst, 0, dst.length);
    }

    public static java.nio.ByteBuffer put(java.nio.ByteBuffer self, int index, byte[] src, int offset, int length) {
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

    public static java.nio.ByteBuffer put(java.nio.ByteBuffer self, int index, byte[] src) {
        return put(self, index, src, 0, src.length);
    }

    public static java.nio.ByteBuffer slice(java.nio.ByteBuffer self, int index, int length) {
        mmt.standin.java.util.Objects.checkFromIndexSize(index, length, self.limit());
        java.nio.ByteBuffer d = self.duplicate();
        d.limit(index + length);
        d.position(index);
        return d.slice();
    }
}
