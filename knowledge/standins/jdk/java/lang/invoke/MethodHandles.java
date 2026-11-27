package mmt.standin.java.lang.invoke;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;

/**
 * java.lang.invoke.MethodHandles members Java 8 lacks, from the JDK's documented contract.
 *
 * privateLookupIn(target, caller) (Java 9) "returns a lookup object on a target class to emulate all supported bytecode
 * behaviors, including private access": on Java 8, which has no modules to check, a lookup on the target with every
 * access mode, as Java 8's own Lookup makes one for a class's own code; IllegalArgumentException for a primitive or array
 * class. empty(type) (Java 9) "produces a method handle of the requested type which ignores any arguments, does nothing,
 * and returns a suitable default depending on the return type. That is, it returns a zero primitive value, a null, or
 * void"; zero(type) (Java 9) the handle of no arguments that returns that default.
 */
public final class MethodHandles {

    private MethodHandles() {}

    public static java.lang.invoke.MethodHandles.Lookup privateLookupIn(Class<?> target, java.lang.invoke.MethodHandles.Lookup caller) throws IllegalAccessException {
        if (target == null || caller == null) {
            throw new NullPointerException();
        }
        if (target.isPrimitive()) {
            throw new IllegalArgumentException(target + " is a primitive class");
        }
        if (target.isArray()) {
            throw new IllegalArgumentException(target + " is an array class");
        }
        // A Java that has privateLookupIn (and the modules it checks) answers it itself.
        java.lang.reflect.Method own;
        try {
            own = java.lang.invoke.MethodHandles.class.getMethod("privateLookupIn", Class.class, java.lang.invoke.MethodHandles.Lookup.class);
        } catch (NoSuchMethodException java8) {
            return MethodHandles$Lookup.full(target);
        }
        try {
            return (java.lang.invoke.MethodHandles.Lookup) MethodHandles$Lookup.call(own, null, target, caller);
        } catch (ClassNotFoundException never) {
            throw new IllegalStateException(never);
        }
    }

    public static MethodHandle empty(MethodType type) {
        if (type == null) {
            throw new NullPointerException();
        }
        return java.lang.invoke.MethodHandles.dropArguments(zero(type.returnType()), 0, type.parameterList());
    }

    public static MethodHandle zero(Class<?> type) {
        if (type == null) {
            throw new NullPointerException();
        }
        if (type == void.class) {
            try {
                return java.lang.invoke.MethodHandles.lookup().findStatic(MethodHandles.class, "nothing", MethodType.methodType(void.class));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        Object value = type.isPrimitive() ? java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(type, 1), 0) : null;
        return java.lang.invoke.MethodHandles.constant(type, value);
    }

    static void nothing() {}
}
