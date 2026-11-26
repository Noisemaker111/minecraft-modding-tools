package java.io;

/**
 * Behind start-check's wall (tools/nogame/MmtWall.java) a start may not read the drive's root, and Java asks the root
 * how long a file name may be each time it makes a temporary file (File.createTempFile, which the game uses to save
 * its level and JNA to unpack its library). Refused that, it answers 255, what NTFS allows, where Java would give up
 * on the file. Put into java.base and made the file system java.io uses (MmtPathPatch points DefaultFileSystem here);
 * only walled starts on Windows run with it.
 */
final class MmtFileSystem extends WinNTFileSystem {

    private static final int NTFS_NAME = 255;

    public int getNameMax(String path) {
        try {
            return super.getNameMax(path);
        } catch (Exception refused) {
            return NTFS_NAME;
        }
    }
}
