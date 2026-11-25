package mmt.standin.java.lang;

/**
 * Java 9's StackTraceElement members, for a release on Java 8, from the JDK's documented contract: the class loader name,
 * module name and module version are each "null if ... not available" ("the module ... is unnamed", "the class loader is
 * the built-in ... or not named"). Java 8 has no modules and names no class loaders, so for every element they are null;
 * the seven-argument constructor keeps the class, method, file and line, which is the four-argument one Java 8 has.
 */
public final class StackTraceElement {

    private StackTraceElement() {}

    public static java.lang.StackTraceElement init(java.lang.String classLoaderName, java.lang.String moduleName, java.lang.String moduleVersion,
        java.lang.String declaringClass, java.lang.String methodName, java.lang.String fileName, int lineNumber) {
        return new java.lang.StackTraceElement(declaringClass, methodName, fileName, lineNumber);
    }

    public static java.lang.String getClassLoaderName(java.lang.StackTraceElement self) {
        return null;
    }

    public static java.lang.String getModuleName(java.lang.StackTraceElement self) {
        return null;
    }

    public static java.lang.String getModuleVersion(java.lang.StackTraceElement self) {
        return null;
    }
}
