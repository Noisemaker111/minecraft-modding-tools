package mmt.standin.java.lang.ref;

/** Java 16's Reference.refersTo(obj): "Tests if this reference object refers to obj", which get() == obj answers. */
public final class Reference {

    private Reference() {}

    public static <T> boolean refersTo(java.lang.ref.Reference<T> self, T obj) {
        return self.get() == obj;
    }
}
