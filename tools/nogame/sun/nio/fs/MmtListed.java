package sun.nio.fs;

import java.lang.reflect.Field;

/**
 * Behind start-check's wall (tools/nogame/MmtWall.java) a start may not list the drive's root or the folder of user
 * profiles: Windows opens those to no container, and only an administrator could change that. Java's real-path check
 * (Path.toRealPath, which every loader calls on its jars) reads each folder of a path by listing the one above it, so
 * it would fail for every file. Here that one lookup, when it is refused, answers with the name as it was written and
 * goes on: the same answer File.getCanonicalPath gives there by itself. Put into java.base beside the class that
 * calls it (MmtPathPatch points the call here); only walled starts on Windows run with it.
 */
final class MmtListed {

    private static final int ACCESS_DENIED = 5, DIRECTORY = 0x10;

    private MmtListed() {}

    static WindowsNativeDispatcher.FirstFile FindFirstFile(String path) throws WindowsException {
        try {
            return WindowsNativeDispatcher.FindFirstFile(path);
        } catch (WindowsException refused) {
            if (refused.lastError() != ACCESS_DENIED) throw refused;
            try {
                // A lookup that works, for the handle its caller closes: the start's own folder is its to list.
                WindowsNativeDispatcher.FirstFile found = WindowsNativeDispatcher.FindFirstFile(System.getProperty("user.dir") + "\\*");
                set(found, "name", path.substring(path.lastIndexOf('\\') + 1));
                set(found, "attributes", DIRECTORY);
                return found;
            } catch (ReflectiveOperationException | RuntimeException unknown) {
                throw refused;
            }
        }
    }

    private static void set(Object found, String name, Object value) throws ReflectiveOperationException {
        Field field = found.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(found, value);
    }
}
