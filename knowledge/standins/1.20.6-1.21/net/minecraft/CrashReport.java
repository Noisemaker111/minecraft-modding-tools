package mmt.standin.net.minecraft;

import java.io.File;
import java.nio.file.Path;
import net.minecraft.ReportType;

/**
 * 1.20.6's CrashReport on 1.21.
 *
 * Read from both releases: 1.20.6's getFriendlyReport() wrote the crash report under the header "---- Minecraft Crash
 * Report ----" and a comment, saveToFile(file) wrote that report to the file once (false when already saved or on
 * failure), and getSaveFile() gave the file. 1.21 takes the kind of report: getFriendlyReport(ReportType.CRASH) and
 * saveToFile(path, ReportType.CRASH) write the same header (ReportType.CRASH.appendHeader), and getSaveFile() gives a Path.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0): "getFriendlyReport and saveToFile now takes in a ReportType and a list of header strings ... There is also an
 * overload that just takes in the ReportType", "getSaveFile now returns a Path".
 */
public final class CrashReport {

    private CrashReport() {}

    public static String getFriendlyReport(net.minecraft.CrashReport self) {
        return self.getFriendlyReport(ReportType.CRASH);
    }

    public static boolean saveToFile(net.minecraft.CrashReport self, File file) {
        return self.saveToFile(file.toPath(), ReportType.CRASH);
    }

    public static File getSaveFile(net.minecraft.CrashReport self) {
        Path path = self.getSaveFile();
        return path == null ? null : path.toFile();
    }
}
