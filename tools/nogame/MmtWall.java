import java.io.IOException;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Starts one command where it can reach no network and show no window, on Windows, with no administrator rights:
 * - in an AppContainer with no capability: Windows itself refuses every connection the process or a child of it
 *   opens, to the internet, the local network and other programs on this machine, and every name lookup, whatever
 *   code opens it (Java, native code, a child process). JVM proxy settings stop only the classes that read them.
 * - on a desktop of its own that is never shown: a window any code makes there is on no screen.
 * The container reads only what it is given: each --read path (a folder and what is in it, or a file) and each
 * --write path. It may list no folder above those (the drive's root and the folder of user profiles are open to no
 * container), which Java's real-path check and its temporary files need: MmtListed and MmtFileSystem under this folder
 * answer for those. Written in Java and run on a signed Java (22 or later, for its calls into Windows) because Smart App
 * Control blocks a program compiled on the machine it runs on.
 * Usage: MmtWall --name NAME [--read PATH]... [--write PATH]... --command FILE (the command, one argument a line)
 */
public final class MmtWall {

    static final Linker LINKER = Linker.nativeLinker();
    static final Arena ARENA = Arena.global();
    static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
    static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG;
    static final AddressLayout POINTER = ValueLayout.ADDRESS;
    /** What this launcher ends with when it could not start its command walled; StartCheck reads it as no start at all. */
    static final int DOWN = 113;

    static MethodHandle windows(String library, String name, ValueLayout result, ValueLayout... arguments) {
        MemorySegment function = SymbolLookup.libraryLookup(library, ARENA).find(name).orElseThrow(() -> new IllegalStateException(library + " has no " + name));
        return LINKER.downcallHandle(function, FunctionDescriptor.of(result, arguments));
    }

    static MemorySegment wide(String text) {
        return ARENA.allocateFrom(text, StandardCharsets.UTF_16LE);
    }

    static void must(boolean ok, String what) throws IOException {
        if (!ok) throw new IOException(what);
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (Throwable failed) {
            System.err.println("mmt-wall: " + failed);
            code = DOWN;
        }
        System.exit(code);
    }

    static int run(String[] args) throws Throwable {
        String name = null;
        Path commandFile = null;
        List<Path> read = new ArrayList<>(), write = new ArrayList<>();
        for (int at = 0; at + 1 < args.length; at += 2) {
            switch (args[at]) {
                case "--name" -> name = args[at + 1];
                case "--read" -> read.add(Path.of(args[at + 1]));
                case "--write" -> write.add(Path.of(args[at + 1]));
                case "--command" -> commandFile = Path.of(args[at + 1]);
                default -> throw new IllegalArgumentException("unknown option " + args[at]);
            }
        }
        if (name == null || commandFile == null) throw new IllegalArgumentException("usage: MmtWall --name NAME [--read PATH]... [--write PATH]... --command FILE");
        StringBuilder commandLine = new StringBuilder();
        for (String argument : Files.readAllLines(commandFile, StandardCharsets.UTF_8)) commandLine.append(commandLine.isEmpty() ? "" : " ").append(quoted(argument));

        MemorySegment sidAt = ARENA.allocate(POINTER);
        int made = (int) windows("userenv", "CreateAppContainerProfile", INT, POINTER, POINTER, POINTER, POINTER, INT, POINTER)
            .invoke(wide(name), wide(name), wide("mmt start-check: a mod start that can reach no network"), MemorySegment.NULL, 0, sidAt);
        // Any other answer than made: the profile is there from an earlier start, and its identifier is worked out from its name.
        if (made != 0) {
            int derived = (int) windows("userenv", "DeriveAppContainerSidFromAppContainerName", INT, POINTER, POINTER).invoke(wide(name), sidAt);
            must(derived == 0, "Windows made no AppContainer named " + name + " (0x" + Integer.toHexString(made) + ", 0x" + Integer.toHexString(derived) + ")");
        }
        MemorySegment sid = sidAt.get(POINTER, 0);
        MemorySegment textAt = ARENA.allocate(POINTER);
        must((int) windows("advapi32", "ConvertSidToStringSidW", INT, POINTER, POINTER).invoke(sid, textAt) != 0, "the container's identifier could not be read");
        String container = textAt.get(POINTER, 0).reinterpret(512).getString(0, StandardCharsets.UTF_16LE);
        for (Path path : read) grant(path, container, false);
        for (Path path : write) grant(path, container, true);

        // SECURITY_CAPABILITIES: the container's identifier and no capability at all.
        MemorySegment capabilities = ARENA.allocate(24, 8);
        capabilities.set(POINTER, 0, sid);
        MemorySegment size = ARENA.allocate(LONG);
        MethodHandle initialize = windows("kernel32", "InitializeProcThreadAttributeList", INT, POINTER, INT, INT, POINTER);
        initialize.invoke(MemorySegment.NULL, 1, 0, size);
        MemorySegment attributes = ARENA.allocate(size.get(LONG, 0), 16);
        must((int) initialize.invoke(attributes, 1, 0, size) != 0, "no process attribute list");
        // 0x00020009: PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES.
        must((int) windows("kernel32", "UpdateProcThreadAttribute", INT, POINTER, INT, LONG, POINTER, LONG, POINTER, POINTER)
            .invoke(attributes, 0, 0x00020009L, capabilities, 24L, MemorySegment.NULL, MemorySegment.NULL) != 0, "the container could not be put on the process");

        // A desktop of this start's own, open to the container (which runs at low integrity) and shown on no screen.
        String desktop = "mmt-wall-" + ProcessHandle.current().pid();
        MemorySegment descriptorAt = ARENA.allocate(POINTER);
        must((int) windows("advapi32", "ConvertStringSecurityDescriptorToSecurityDescriptorW", INT, POINTER, INT, POINTER, POINTER)
            .invoke(wide("D:(A;;GA;;;WD)(A;;GA;;;AC)S:(ML;;NW;;;LW)"), 1, descriptorAt, MemorySegment.NULL) != 0, "no security descriptor for the desktop");
        MemorySegment security = ARENA.allocate(24, 8);
        security.set(INT, 0, 24);
        security.set(POINTER, 8, descriptorAt.get(POINTER, 0));
        // 0x10000000: GENERIC_ALL.
        MemorySegment hidden = (MemorySegment) windows("user32", "CreateDesktopW", POINTER, POINTER, POINTER, POINTER, INT, INT, POINTER)
            .invoke(wide(desktop), MemorySegment.NULL, MemorySegment.NULL, 0, 0x10000000, security);
        must(hidden.address() != 0, "Windows made no desktop for the start");

        // STARTUPINFOEXW: the desktop, this launcher's own input and output, and the attribute list.
        MemorySegment startup = ARENA.allocate(112, 8);
        startup.set(INT, 0, 112);
        startup.set(POINTER, 16, wide(desktop));
        // 0x100: STARTF_USESTDHANDLES.
        startup.set(INT, 60, 0x100);
        MethodHandle standard = windows("kernel32", "GetStdHandle", POINTER, INT);
        startup.set(POINTER, 80, (MemorySegment) standard.invoke(-10));
        startup.set(POINTER, 88, (MemorySegment) standard.invoke(-11));
        startup.set(POINTER, 96, (MemorySegment) standard.invoke(-12));
        startup.set(POINTER, 104, attributes);

        // The command ends with this launcher, however this launcher ends: JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE (0x2000) in JOBOBJECT_EXTENDED_LIMIT_INFORMATION (class 9).
        MemorySegment job = (MemorySegment) windows("kernel32", "CreateJobObjectW", POINTER, POINTER, POINTER).invoke(MemorySegment.NULL, MemorySegment.NULL);
        MemorySegment limits = ARENA.allocate(144, 8);
        limits.set(INT, 16, 0x2000);
        must((int) windows("kernel32", "SetInformationJobObject", INT, POINTER, INT, POINTER, INT).invoke(job, 9, limits, 144) != 0, "no job for the start");

        MemorySegment process = ARENA.allocate(24, 8);
        // EXTENDED_STARTUPINFO_PRESENT | CREATE_SUSPENDED | CREATE_NO_WINDOW | CREATE_UNICODE_ENVIRONMENT.
        int flags = 0x00080000 | 0x4 | 0x08000000 | 0x400;
        MethodHandle create = LINKER.downcallHandle(SymbolLookup.libraryLookup("kernel32", ARENA).find("CreateProcessW").orElseThrow(),
            FunctionDescriptor.of(INT, POINTER, POINTER, POINTER, POINTER, INT, INT, POINTER, POINTER, POINTER, POINTER), Linker.Option.captureCallState("GetLastError"));
        MemorySegment state = ARENA.allocate(Linker.Option.captureStateLayout());
        int started = (int) create.invoke(state, MemorySegment.NULL, wide(commandLine.toString()), MemorySegment.NULL, MemorySegment.NULL, 1, flags, MemorySegment.NULL, MemorySegment.NULL, startup, process);
        int error = (int) Linker.Option.captureStateLayout().varHandle(java.lang.foreign.MemoryLayout.PathElement.groupElement("GetLastError")).get(state, 0L);
        must(started != 0, "Windows did not start the command in its container (error " + error + "): " + commandLine);
        MemorySegment handle = process.get(POINTER, 0);
        must((int) windows("kernel32", "AssignProcessToJobObject", INT, POINTER, POINTER).invoke(job, handle) != 0, "the start could not be tied to this launcher");
        int resumed = (int) windows("kernel32", "ResumeThread", INT, POINTER).invoke(process.get(POINTER, 8));
        must(resumed != -1, "the start could not be let run");
        int waited = (int) windows("kernel32", "WaitForSingleObject", INT, POINTER, INT).invoke(handle, -1);
        MemorySegment code = ARENA.allocate(INT);
        must(waited == 0 && (int) windows("kernel32", "GetExitCodeProcess", INT, POINTER, POINTER).invoke(handle, code) != 0, "the start's end could not be read");
        return code.get(INT, 0);
    }

    /** One argument as Windows' own command-line rules read it back unchanged. */
    static String quoted(String argument) {
        if (!argument.isEmpty() && argument.chars().noneMatch(c -> c == ' ' || c == '\t' || c == '"')) return argument;
        StringBuilder out = new StringBuilder("\"");
        int slashes = 0;
        for (char c : argument.toCharArray()) {
            if (c == '\\') {
                slashes++;
                continue;
            }
            // Backslashes count as themselves unless a quote follows them: then each is doubled, and the quote escaped.
            out.append("\\".repeat(c == '"' ? slashes * 2 + 1 : slashes));
            slashes = 0;
            out.append(c);
        }
        return out.append("\\".repeat(slashes * 2)).append('"').toString();
    }

    static boolean may(Path path, String container, List<AclEntryPermission> needed) throws IOException {
        for (AclEntry entry : Files.getFileAttributeView(path, AclFileAttributeView.class).getAcl()) {
            // Windows' own folders (Program Files, where a Java may be) are open to every container already, and are not this user's to change.
            boolean ours = entry.principal().getName().equals(container) || entry.principal().getName().endsWith("\\ALL APPLICATION PACKAGES");
            if (ours && entry.type() == AclEntryType.ALLOW && entry.permissions().containsAll(needed)) return true;
        }
        return false;
    }

    /** The container may read (or change) a path and what is in it. Nothing is written where it already may. */
    static void grant(Path path, String container, boolean change) throws IOException, InterruptedException {
        if (!Files.exists(path)) throw new IOException("no such path to open to the container: " + path);
        List<AclEntryPermission> needed = change ? List.of(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA, AclEntryPermission.DELETE) : List.of(AclEntryPermission.READ_DATA, AclEntryPermission.EXECUTE);
        if (may(path, container, needed)) return;
        String rights = (Files.isDirectory(path) ? "(OI)(CI)" : "") + (change ? "(M)" : "(RX)");
        Path icacls = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "icacls.exe");
        // icacls hands the permission on to what is already in the folder, so it is only ever run on a folder a start is made from. /Q: only failures are said.
        Process granted = new ProcessBuilder(icacls.toString(), path.toString(), "/grant", "*" + container + ":" + rights, "/Q", "/C").redirectErrorStream(true).start();
        String said = new String(granted.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (!granted.waitFor(30, TimeUnit.MINUTES) || granted.exitValue() != 0) throw new IOException("could not open " + path + " to the container: " + said);
    }
}
