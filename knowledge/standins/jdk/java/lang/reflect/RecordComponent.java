package mmt.standin.java.lang.reflect;

import java.lang.reflect.Method;

/**
 * Java 16's java.lang.reflect.RecordComponent, for a record ported to a Java without records (Class.getRecordComponents
 * makes these), from the JDK's documented contract: "a record component ... provides information about, and dynamic
 * access to, a component of a record class". getName() "the name of this record component"; getType() "a Class that
 * identifies the declared type", and getGenericType() "a Type object that represents the declared type", which javac
 * writes as the return type of the component's accessor, read from it; getGenericSignature() "the generic signature of
 * this record component", or null when its type is neither a type variable nor a parameterized type; getAccessor() "a
 * Method that represents the accessor"; getDeclaringRecord() "the record class which declares this record component";
 * toString() "the type name followed by a space and the name". A component's annotations are not kept by the port, so
 * the AnnotatedElement methods are not written here: a mod asking for them keeps that as a named break.
 */
public final class RecordComponent {

    private final Class<?> record;
    private final String name;
    private final Method accessor;

    public RecordComponent(Class<?> record, String name, Method accessor) {
        this.record = record;
        this.name = name;
        this.accessor = accessor;
    }

    public String getName() {
        return name;
    }

    public Class<?> getType() {
        return accessor.getReturnType();
    }

    public java.lang.reflect.Type getGenericType() {
        return accessor.getGenericReturnType();
    }

    public String getGenericSignature() {
        java.lang.reflect.Type t = getGenericType();
        return t instanceof Class ? null : signature(t);
    }

    public Method getAccessor() {
        return accessor;
    }

    public Class<?> getDeclaringRecord() {
        return record;
    }

    @Override
    public String toString() {
        return getType().getTypeName() + " " + name;
    }

    /** A type as the Signature attribute writes it (JVMS 4.7.9.1). */
    static String signature(java.lang.reflect.Type t) {
        if (t instanceof Class) {
            Class<?> c = (Class<?>) t;
            if (c.isArray()) {
                return "[" + signature(c.getComponentType());
            }
            if (c.isPrimitive()) {
                return mmt.standin.java.lang.Class.descriptorString(c);
            }
            return "L" + c.getName().replace('.', '/') + ";";
        }
        if (t instanceof java.lang.reflect.TypeVariable) {
            return "T" + ((java.lang.reflect.TypeVariable<?>) t).getName() + ";";
        }
        if (t instanceof java.lang.reflect.GenericArrayType) {
            return "[" + signature(((java.lang.reflect.GenericArrayType) t).getGenericComponentType());
        }
        if (t instanceof java.lang.reflect.WildcardType) {
            java.lang.reflect.WildcardType w = (java.lang.reflect.WildcardType) t;
            if (w.getLowerBounds().length > 0) {
                return "-" + signature(w.getLowerBounds()[0]);
            }
            java.lang.reflect.Type[] upper = w.getUpperBounds();
            return upper.length == 0 || upper[0] == Object.class ? "*" : "+" + signature(upper[0]);
        }
        java.lang.reflect.ParameterizedType p = (java.lang.reflect.ParameterizedType) t;
        StringBuilder out = new StringBuilder();
        Class<?> raw = (Class<?>) p.getRawType();
        if (p.getOwnerType() instanceof java.lang.reflect.ParameterizedType) {
            String owner = signature(p.getOwnerType());
            out.append(owner, 0, owner.length() - 1).append('.').append(raw.getSimpleName());
        } else {
            out.append('L').append(raw.getName().replace('.', '/'));
        }
        // A class nested in a generic one has no arguments of its own (Outer<String>.Inner): no "<>", which no signature has.
        java.lang.reflect.Type[] args = p.getActualTypeArguments();
        if (args.length > 0) {
            out.append('<');
            for (java.lang.reflect.Type a : args) {
                out.append(signature(a));
            }
            out.append('>');
        }
        return out.append(';').toString();
    }
}
