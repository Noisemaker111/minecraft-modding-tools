package mmt.standin.java.lang;

/** Java 16's IndexOutOfBoundsException(int index): "an IndexOutOfBoundsException with an argument indicating the illegal index. The index is included in this exception's detail message", which is "Index out of range: " + index. */
public final class IndexOutOfBoundsException {

    private IndexOutOfBoundsException() {}

    public static java.lang.IndexOutOfBoundsException init(int index) {
        return new java.lang.IndexOutOfBoundsException("Index out of range: " + index);
    }
}
