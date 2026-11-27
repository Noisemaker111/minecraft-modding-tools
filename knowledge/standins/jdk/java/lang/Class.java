package mmt.standin.java.lang;

/**
 * java.lang.Class members Java 8 lacks, from the JDK's documented contract: getPackageName() (Java 9) "the fully qualified
 * package name" of the class, of the element type for an array, "java.lang" for a primitive type or void, "" in the
 * unnamed package; componentType() (Java 12) getComponentType(); arrayType() (Java 12) the class of an array of this
 * type; descriptorString() (Java 12) "the descriptor string for this Class object" ("I" for int, "[I" for int[],
 * "Ljava/lang/String;" for String); isSealed() (Java 17) false, since Java 8 has no sealed classes, and
 * getPermittedSubclasses() (Java 17) null, as for any class that is not sealed.
 *
 * isRecord() and getRecordComponents() (Java 16): Java 8 has no records, but a mod's records ported to it are plain
 * classes that keep their component names, in declaration order, in a constant the port writes (Downgrade's
 * RECORD_COMPONENTS). isRecord() is "true if and only if this class is a record class", which is a class holding that
 * constant; getRecordComponents() "the record components of this record class ... in the same order that they are
 * declared", or null "if this class is not a record class". A component's type and generic type are its accessor's
 * return types, as javac writes the accessor of every component.
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

    public static java.lang.String descriptorString(java.lang.Class<?> self) {
        if (self.isArray()) {
            return "[" + descriptorString(self.getComponentType());
        }
        if (self.isPrimitive()) {
            return java.lang.String.valueOf(self == void.class ? 'V' : self == boolean.class ? 'Z' : self == byte.class ? 'B' : self == char.class ? 'C'
                : self == short.class ? 'S' : self == int.class ? 'I' : self == long.class ? 'J' : self == float.class ? 'F' : 'D');
        }
        return "L" + self.getName().replace('.', '/') + ";";
    }

    /** The fields Downgrade.RECORD_COMPONENTS and RECORD_COMPONENT name on a class lowered from a record. */
    static final java.lang.String COMPONENTS = "mmt$recordComponents";
    static final java.lang.String COMPONENT = "mmt$recordComponent$";

    /**
     * A lowered record's component names in order, read from its fields' names (never their values, which would run the
     * class's static initializer, as the JDK's isRecord does not); null for any other class.
     */
    static java.lang.String[] components(java.lang.Class<?> self) {
        if (self.isArray() || self.isPrimitive() || self.isInterface()) {
            return null;
        }
        boolean record = false;
        java.util.TreeMap<java.lang.Integer, java.lang.String> names = new java.util.TreeMap<>();
        for (java.lang.reflect.Field f : self.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) || !f.isSynthetic()) {
                continue;
            }
            java.lang.String n = f.getName();
            if (n.equals(COMPONENTS)) {
                record = true;
            } else if (n.startsWith(COMPONENT)) {
                java.lang.String rest = n.substring(COMPONENT.length());
                int dollar = rest.indexOf('$');
                names.put(java.lang.Integer.parseInt(rest.substring(0, dollar)), rest.substring(dollar + 1));
            }
        }
        return record ? names.values().toArray(new java.lang.String[0]) : null;
    }

    public static boolean isRecord(java.lang.Class<?> self) {
        return components(self) != null;
    }

    public static mmt.standin.java.lang.reflect.RecordComponent[] getRecordComponents(java.lang.Class<?> self) {
        java.lang.String[] each = components(self);
        if (each == null) {
            return null;
        }
        mmt.standin.java.lang.reflect.RecordComponent[] out = new mmt.standin.java.lang.reflect.RecordComponent[each.length];
        for (int i = 0; i < each.length; i++) {
            try {
                out[i] = new mmt.standin.java.lang.reflect.RecordComponent(self, each[i], self.getDeclaredMethod(each[i]));
            } catch (NoSuchMethodException noAccessor) {
                throw new IllegalStateException("record " + self.getName() + " has no accessor for its component " + each[i], noAccessor);
            }
        }
        return out;
    }

    public static boolean isSealed(java.lang.Class<?> self) {
        return false;
    }

    public static java.lang.Class<?>[] getPermittedSubclasses(java.lang.Class<?> self) {
        return null;
    }
}
