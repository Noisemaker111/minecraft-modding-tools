package mmt.standin.java.lang.reflect;

/**
 * Java 9's AccessibleObject.trySetAccessible(): "Set the accessible flag for this reflected object to true if possible",
 * true when it is set, false when it cannot be. Java 8 has no modules, so only a SecurityException from setAccessible
 * refuses it.
 */
public final class AccessibleObject {

    private AccessibleObject() {}

    public static boolean trySetAccessible(java.lang.reflect.AccessibleObject self) {
        try {
            self.setAccessible(true);
            return true;
        } catch (SecurityException refused) {
            return false;
        }
    }
}
