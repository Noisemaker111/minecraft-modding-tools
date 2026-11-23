package mmt.standin.java.lang;

/**
 * java.lang.Class members Java 8 lacks, from the JDK's documented contract: getPackageName() (Java 9) "the fully qualified
 * package name" of the class, of the element type for an array, "java.lang" for a primitive type or void, "" in the
 * unnamed package; componentType() (Java 12) getComponentType(); arrayType() (Java 12) the class of an array of this
 * type; isRecord() and isSealed() (Java 16, 17) false, since Java 8 has neither records nor sealed classes, and
 * getPermittedSubclasses() (Java 17) null, as for any class that is not sealed.
 */
public final class Class {

    private Class() {}

    public static java.lang.String getPackageName(java.lang.Class<?> self) {
        java.lang.Class<?> c = self;
        while (c.isArray()) {
            c = c.getComponentType();
        }
        if (c.isPrimitive()) {
            return "java.lang";
        }
        java.lang.String name = c.getName();
        int dot = name.lastIndexOf('.');
        return dot == -1 ? "" : name.substring(0, dot);
    }

    public static java.lang.Class<?> componentType(java.lang.Class<?> self) {
        return self.getComponentType();
    }

    public static java.lang.Class<?> arrayType(java.lang.Class<?> self) {
        return java.lang.reflect.Array.newInstance(self, 0).getClass();
    }

    public static boolean isRecord(java.lang.Class<?> self) {
        return false;
    }

    public static boolean isSealed(java.lang.Class<?> self) {
        return false;
    }

    public static java.lang.Class<?>[] getPermittedSubclasses(java.lang.Class<?> self) {
        return null;
    }
}
