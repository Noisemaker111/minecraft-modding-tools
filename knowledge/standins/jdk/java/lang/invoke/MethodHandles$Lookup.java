package mmt.standin.java.lang.invoke;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.ProtectionDomain;

/**
 * java.lang.invoke.MethodHandles.Lookup members Java 8 lacks, from the JDK's documented contract.
 *
 * defineClass(bytes) (Java 9) "creates and links a class or interface from bytes with the same class loader and in the
 * same runtime package and protection domain as this lookup's lookup class as if calling ClassLoader::defineClass":
 * IllegalAccessException when the lookup lacks PACKAGE access, IllegalArgumentException when the bytes are no class file
 * or name a class of another package; what ClassLoader.defineClass throws (LinkageError, ClassFormatError) is thrown as
 * it is. Java 8 lets its own code call that protected method; so does this. findClass(name) (Java 9) "looks up a class by
 * name from the lookup context defined by this Lookup object, as if resolved by an ldc instruction": loaded by the lookup
 * class's loader, not initialized, ClassNotFoundException when it has none such, IllegalAccessException when the class is
 * neither public nor in the lookup class's package with PACKAGE access.
 *
 * A Java that has these members (a release's players may run a newer Java than the release ships for) answers them
 * itself: the Java 8 forms open the JDK's internals, which Java 17 and later refuse.
 */
public final class MethodHandles$Lookup {

    private MethodHandles$Lookup() {}

    /** Every access mode Java 8 has: what a class's own MethodHandles.lookup() gets. */
    static final int ALL = Lookup.PUBLIC | Lookup.PRIVATE | Lookup.PROTECTED | Lookup.PACKAGE;

    /** A lookup on target with every access mode, through Java 8's Lookup(Class, int) constructor. */
    static Lookup full(Class<?> target) throws IllegalAccessException {
        try {
            Constructor<Lookup> make = Lookup.class.getDeclaredConstructor(Class.class, int.class);
            make.setAccessible(true);
            return make.newInstance(target, ALL);
        } catch (ReflectiveOperationException | RuntimeException e) {
            IllegalAccessException denied = new IllegalAccessException("no full-privilege lookup on " + target);
            denied.initCause(e);
            throw denied;
        }
    }

    /** The JDK's own member of Lookup, on a Java that has it; null on Java 8. */
    static Method jdks(String name, Class<?>... params) {
        try {
            return Lookup.class.getMethod(name, params);
        } catch (NoSuchMethodException java8) {
            return null;
        }
    }

    /** What the JDK's own member returns, or throws as it threw it. */
    static Object call(Method own, Object self, Object... args) throws IllegalAccessException, ClassNotFoundException {
        try {
            return own.invoke(self, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IllegalAccessException) {
                throw (IllegalAccessException) cause;
            }
            if (cause instanceof ClassNotFoundException) {
                throw (ClassNotFoundException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException(cause);
        }
    }

    public static Class<?> defineClass(Lookup self, byte[] bytes) throws IllegalAccessException {
        Method own = jdks("defineClass", byte[].class);
        if (own != null) {
            try {
                return (Class<?>) call(own, self, (Object) bytes);
            } catch (ClassNotFoundException never) {
                throw new IllegalStateException(never);
            }
        }
        if ((self.lookupModes() & Lookup.PACKAGE) == 0) {
            throw new IllegalAccessException(self + " does not have PACKAGE access");
        }
        String name = className(bytes);
        Class<?> host = self.lookupClass();
        if (!packageOf(name).equals(packageOf(host.getName()))) {
            throw new IllegalArgumentException(name + " not in same package as lookup class");
        }
        ClassLoader loader = host.getClassLoader();
        if (loader == null) {
            throw new IllegalAccessException("the lookup class " + host + " is the boot loader's");
        }
        try {
            Method define = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class, ProtectionDomain.class);
            define.setAccessible(true);
            return (Class<?>) define.invoke(loader, name, bytes, 0, bytes.length, host.getProtectionDomain());
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException(cause);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Class<?> findClass(Lookup self, String targetName) throws ClassNotFoundException, IllegalAccessException {
        Method own = jdks("findClass", String.class);
        if (own != null) {
            return (Class<?>) call(own, self, targetName);
        }
        Class<?> host = self.lookupClass();
        Class<?> found = Class.forName(targetName, false, host.getClassLoader());
        Class<?> element = found;
        while (element.isArray()) {
            element = element.getComponentType();
        }
        boolean samePackage = element.isPrimitive() || packageOf(element.getName()).equals(packageOf(host.getName()))
            && element.getClassLoader() == host.getClassLoader();
        if (!element.isPrimitive() && !Modifier.isPublic(element.getModifiers()) && !(samePackage && (self.lookupModes() & Lookup.PACKAGE) != 0)) {
            throw new IllegalAccessException("access violation: " + found + ", from " + self);
        }
        return found;
    }

    static String packageOf(String binaryName) {
        int dot = binaryName.lastIndexOf('.');
        return dot < 0 ? "" : binaryName.substring(0, dot);
    }

    /** The binary name a class file declares (this_class), read past its constant pool. */
    static String className(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0xCAFEBABE) {
                throw new IllegalArgumentException("not a class file");
            }
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            int[] classNames = new int[count];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1:
                        utf8[i] = in.readUTF();
                        break;
                    case 7:
                        classNames[i] = in.readUnsignedShort();
                        break;
                    case 8: case 16: case 19: case 20:
                        in.readUnsignedShort();
                        break;
                    case 15:
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                        break;
                    case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
                        in.readInt();
                        break;
                    case 5: case 6:
                        in.readLong();
                        i++;
                        break;
                    default:
                        throw new IllegalArgumentException("not a class file: constant pool tag " + tag);
                }
            }
            in.readUnsignedShort();
            int self = in.readUnsignedShort();
            if (self <= 0 || self >= count || classNames[self] == 0 || utf8[classNames[self]] == null) {
                throw new IllegalArgumentException("not a class file: no class name");
            }
            return utf8[classNames[self]].replace('/', '.');
        } catch (IOException e) {
            throw new IllegalArgumentException("not a class file", e);
        }
    }
}
