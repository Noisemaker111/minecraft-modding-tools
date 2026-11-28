package mmt.standin.net.minecraft;

/**
 * 1.21's CrashReport.getFriendlyReport and saveToFile with a ReportType on 1.20.6, for a port back.
 *
 * Read from both releases: 1.21's getFriendlyReport(type, extra) is the type's header (see the stand-in ReportType)
 * followed by the time, description, exception and details; 1.20.6's getFriendlyReport() is the crash header followed by
 * the same, up to its first blank line, so that header is swapped for the type's. 1.21's saveToFile(path, type, extra)
 * writes that text once (false when saved before) and keeps the path; 1.20.6's saveToFile(File) writes its crash text and
 * keeps the file, which is used for a crash report with no extra lines, and the text written here for any other.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`getFriendlyReport` and `saveToFile` now takes in a `ReportType` and a list of header strings".
 */
public final class CrashReport {

    private CrashReport() {}

    public static String getFriendlyReport(net.minecraft.CrashReport self, ReportType type, java.util.List extra) {
        String report = self.getFriendlyReport();
        StringBuilder out = new StringBuilder();
        type.appendHeader(out, extra);
        int body = report.indexOf("\n\n");
        out.append(body < 0 ? report : report.substring(body + 2));
        return out.toString();
    }

    public static String getFriendlyReport(net.minecraft.CrashReport self, ReportType type) {
        return getFriendlyReport(self, type, java.util.List.of());
    }

    public static boolean saveToFile(net.minecraft.CrashReport self, java.nio.file.Path path, ReportType type, java.util.List extra) {
        if (type == ReportType.CRASH && extra.isEmpty()) {
            return self.saveToFile(path.toFile());
        }
        if (self.getSaveFile() != null) {
            return false;
        }
        try {
            if (path.getParent() != null) {
                java.nio.file.Files.createDirectories(path.getParent());
            }
            java.nio.file.Files.writeString(path, getFriendlyReport(self, type, extra));
            return true;
        } catch (Throwable failed) {
            com.mojang.logging.LogUtils.getLogger().error("Could not save crash report to {}", path, failed);
            return false;
        }
    }

    public static boolean saveToFile(net.minecraft.CrashReport self, java.nio.file.Path path, ReportType type) {
        return saveToFile(self, path, type, java.util.List.of());
    }
}
