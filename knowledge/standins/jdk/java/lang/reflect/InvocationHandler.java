package mmt.standin.java.lang.reflect;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;

/**
 * Java 16's InvocationHandler.invokeDefault(proxy, method, args), for a release on an older Java, from the JDK's
 * documented contract: "invokes the specified default method on the given proxy instance with the given parameters ...
 * as if it were invoked by an invokespecial instruction from the proxy class". IllegalArgumentException "if proxy is not
 * a proxy instance", if the method "is not a default method declared in a proxy interface of the proxy class", or if
 * the arguments do not match its parameters; NullPointerException if proxy or method is null; what the default method
 * throws is thrown as it is. The default method is reached through a lookup with private access in its interface (Java
 * 9's MethodHandles.privateLookupIn, or Java 8's full-privilege Lookup), the way proxies called one before Java 16. The
 * JDK's own invokeDefault checks access from its caller, which is this class and not the mod, so it is called only for a
 * public interface (one of the JDK's, which no lookup of a mod's may open), on a Java that has it.
 */
public final class InvocationHandler {

    private InvocationHandler() {}

    public static Object invokeDefault(Object proxy, Method method, Object... args) throws Throwable {
        Objects.requireNonNull(proxy);
        Objects.requireNonNull(method);
        if (!Proxy.isProxyClass(proxy.getClass())) {
            throw new IllegalArgumentException("'proxy' is not a proxy instance");
        }
        Class<?> intf = method.getDeclaringClass();
        if (!method.isDefault() || !intf.isInstance(proxy)) {
            throw new IllegalArgumentException("\"" + method + "\" is not a default method declared in the proxy class");
        }
        Object[] params = args == null ? new Object[0] : args;
        if (params.length != method.getParameterCount()) {
            throw new IllegalArgumentException("wrong number of parameters: " + params.length + " for " + method);
        }
        Method own = java.lang.reflect.Modifier.isPublic(intf.getModifiers()) ? jdks() : null;
        if (own != null) {
            try {
                return own.invoke(null, proxy, method, params);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
        return lookup(intf).unreflectSpecial(method, intf).bindTo(proxy).invokeWithArguments(params);
    }

    /** The JDK's own invokeDefault, on a Java that has it (16 and later). */
    static Method jdks() {
        try {
            return java.lang.reflect.InvocationHandler.class.getMethod("invokeDefault", Object.class, Method.class, Object[].class);
        } catch (NoSuchMethodException older) {
            return null;
        }
    }

    /** A lookup on intf with private access: Java 9's privateLookupIn where there is one, else Java 8's Lookup(Class, int). */
    static MethodHandles.Lookup lookup(Class<?> intf) throws ReflectiveOperationException {
        try {
            Method in = MethodHandles.class.getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class);
            return (MethodHandles.Lookup) in.invoke(null, intf, MethodHandles.lookup());
        } catch (NoSuchMethodException java8) {
            Constructor<MethodHandles.Lookup> make = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, int.class);
            make.setAccessible(true);
            // Every access mode, as the interface's own code has: a package-private interface needs PACKAGE as well as PRIVATE.
            return make.newInstance(intf, MethodHandles.Lookup.PUBLIC | MethodHandles.Lookup.PRIVATE | MethodHandles.Lookup.PROTECTED | MethodHandles.Lookup.PACKAGE);
        }
    }
}
