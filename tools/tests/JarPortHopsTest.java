import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * A port across several updates crosses each update that has stand-ins on its own, so that update's stand-ins answer
 * the members it dropped, and a later update ports them like the mod's own code. Gauge.read() went in 2.0 (its stand-in
 * calls doubled()), and doubled() went in 3.0 (its stand-in calls twice()): a mod calling read() ported from 1.0 to 3.0
 * runs both stand-ins, carried side by side under packages of their own though both stand in for Gauge, whether the
 * hops keep the jar's name (JarPort's own hops) or each port is given the last one's output under a new name (mmt port
 * --jar run twice).
 */
final class JarPortHopsTest {

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        Path root = scratch.resolve("hops-kb");
        Path book = Files.createDirectories(root).resolve("fixes.json");
        Files.writeString(book, "{\"rules\": []}");
        Path standins = root.resolve("standins");
        JarPortStandinTest.write(standins.resolve("1.5-2.0/s9/Gauge.java"), """
            package mmt.standin.s9;
            public final class Gauge {
                public static int read(s9.Gauge self) {
                    return self.doubled() / 2;
                }
            }
            """);
        // Zone went in 2.0 too, with nothing in its place: its stand-in is the class, carried in 2.0's jar of types.
        JarPortStandinTest.write(standins.resolve("1.5-2.0/s9/Zone.java"), """
            package mmt.standin.s9;
            public final class Zone {
                public int size() {
                    return 3;
                }
            }
            """);
        JarPortStandinTest.write(standins.resolve("2.0-3.0/s9/Gauge.java"), """
            package mmt.standin.s9;
            public final class Gauge {
                public static int doubled(s9.Gauge self) {
                    return self.twice();
                }
            }
            """);
        // Another loader's stand-ins make no stop for this one.
        JarPortStandinTest.write(standins.resolve("1.0-1.2.fabric/s9/Gauge.java"), "package mmt.standin.s9;\npublic final class Gauge {}\n");

        List<String> chain = List.of("1.0", "1.2", "1.5", "2.0", "3.0");
        List<String> stops = JarPort.hops(chain, standins, "neoforge");
        JarPortTest.expect(stops.equals(List.of("1.0", "1.5", "2.0", "3.0")),
            "each update with stand-ins is crossed on its own and the updates without are crossed in one step: " + stops);
        JarPortTest.expect(JarPort.hops(List.of("1.0", "1.2", "1.5"), standins, "neoforge").equals(List.of("1.0", "1.5")),
            "a route with no stand-ins is one step");
        JarPortTest.expect(JarPort.hops(chain, standins, "fabric").equals(List.of("1.0", "1.2", "1.5", "2.0", "3.0")),
            "a loader's own stand-ins make a stop for it: " + JarPort.hops(chain, standins, "fabric"));

        Map<String, GameDiff.Snap> snaps = new HashMap<>();
        for (String v : List.of("1.0", "1.5", "2.0", "3.0")) {
            GameDiff.Snap s = new GameDiff.Snap(v);
            s.loaderVersion = v + ".1";
            JarPortTest.snap(s, "s9/Gauge", 0, null, "<init>()V", switch (v) {
                case "2.0" -> "doubled()I";
                case "3.0" -> "twice()I";
                default -> "read()I";
            });
            if (v.equals("1.0") || v.equals("1.5")) {
                JarPortTest.snap(s, "s9/Zone", 0, null, "<init>()V");
            }
            snaps.put(v, s);
        }
        Path map = scratch.resolve("route-hops.json");
        Files.writeString(map, "{}");

        for (boolean renamed : List.of(false, true)) {
            byte[] jar = JarPortTest.jar(Map.of("n9/Mod.class", mod(),
                "META-INF/neoforge.mods.toml", "modLoader=\"javafml\"\n[[mods]]\nmodId=\"n9\"\n".getBytes(StandardCharsets.UTF_8)));
            List<Map<String, Object>> runs = new ArrayList<>();
            for (int i = 0; i + 1 < stops.size(); i++) {
                JarPort hop = new JarPort(snaps.get(stops.get(i)), snaps.get(stops.get(i + 1)), map);
                hop.fixes(book);
                jar = hop.port(jar, renamed ? "mod-" + stops.get(i) + ".jar" : "mod.jar", runs);
            }
            String how = renamed ? "each port given the last one's jar under a new name" : "one jar's hops";
            List<String> unlinked = runs.stream().flatMap(r -> Json.array(r.get("unlinked")).stream()).map(String::valueOf).toList();
            JarPortTest.expect(unlinked.isEmpty(), how + ": every hop links: " + unlinked);
            Map<String, byte[]> ported = JarPortTest.entries(jar);
            List<String> carried = ported.keySet().stream().filter(n -> n.startsWith("mmt/standin/")).sorted().toList();
            JarPortTest.expect(carried.size() == 2, how + ": both updates' stand-ins for Gauge are carried, each under its own package: " + carried);
            Map<String, byte[]> classes = new HashMap<>(game());
            Map<String, byte[]> all = new HashMap<>(ported);
            all.putAll(JarPortStandinTest.types(ported, false));
            for (var e : all.entrySet()) {
                if (e.getKey().endsWith(".class")) {
                    classes.put(e.getKey().substring(0, e.getKey().length() - 6), e.getValue());
                }
            }
            String trace;
            try {
                ClassLoader loader = JarPortStandinTest.loader(classes);
                Class<?> mod = loader.loadClass("n9.Mod"), gauge = loader.loadClass("s9.Gauge");
                Object zone = mod.getMethod("zone").invoke(null);
                trace = mod.getMethod("read", gauge).invoke(null, gauge.getConstructor().newInstance()) + " " + zone.getClass().getMethod("size").invoke(zone);
            } catch (ReflectiveOperationException | LinkageError failed) {
                trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
            }
            JarPortTest.expect(trace.equals("42 3"), how + ": the mod runs 2.0's stand-ins, ported onto 3.0 by 3.0's: " + trace);
        }

        // JarPort's own steps hand each hop's jar to the next as its files, never zipped: the jar it ends with is the one
        // zipping each hop's jar and reading it back gives, byte for byte. The shipped jar's times are not on the zip's
        // two-second grid, and it has a folder with no files (META-INF/jarjar/), which the first hop's jar leaves out and
        // the hop carrying the stand-in types writes again, with no time of its own.
        byte[] shipped = timed();
        byte[] zipped = shipped;
        JarEntries.Unpacked files = null;
        for (int i = 0; i + 1 < stops.size(); i++) {
            JarPort hop = new JarPort(snaps.get(stops.get(i)), snaps.get(stops.get(i + 1)), map);
            hop.fixes(book);
            zipped = hop.port(zipped, "mod.jar", new ArrayList<>());
            JarPort handing = new JarPort(snaps.get(stops.get(i)), snaps.get(stops.get(i + 1)), map);
            handing.fixes(book);
            files = handing.step(i == 0 ? handing.read(shipped, "mod.jar") : handing.read(files, "mod.jar"), "mod.jar", new ArrayList<>());
        }
        byte[] handed = files.zip(java.util.zip.Deflater.DEFAULT_COMPRESSION);
        JarPortTest.expect(Arrays.equals(zipped, handed), "a port handing each step's files on writes the jar zipping each step's jar does ("
            + zipped.length + " and " + handed.length + " bytes)");
        JarPortTest.finish("JarPortHopsTest");
    }

    /** The mod with the times a jar built on a machine has, and a folder with nothing in it. */
    static byte[] timed() throws java.io.IOException {
        long at = 1694133817500L; // 2023-09-08, an odd second and a half
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(buffer)) {
            Object[][] entries = {
                { "META-INF/", null }, { "META-INF/jarjar/", null },
                { "META-INF/neoforge.mods.toml", "modLoader=\"javafml\"\n[[mods]]\nmodId=\"n9\"\n".getBytes(StandardCharsets.UTF_8) },
                { "n9/", null }, { "n9/Mod.class", mod() }, { "assets/n9/lang/en_us.json", "{}".getBytes(StandardCharsets.UTF_8) } };
            for (Object[] e : entries) {
                java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry((String) e[0]);
                at += 61_700;
                entry.setTime(at);
                out.putNextEntry(entry);
                if (e[1] != null) {
                    out.write((byte[]) e[1]);
                }
                out.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    /** 3.0's Gauge: twice() is 84. */
    static Map<String, byte[]> game() {
        ClassWriter g = JarPortStandinTest.start("s9/Gauge");
        MethodVisitor twice = g.visitMethod(Opcodes.ACC_PUBLIC, "twice", "()I", null, null);
        twice.visitCode();
        twice.visitIntInsn(Opcodes.BIPUSH, 84);
        twice.visitInsn(Opcodes.IRETURN);
        twice.visitMaxs(0, 0);
        return Map.of("s9/Gauge", JarPortStandinTest.end(g));
    }

    /** The mod, written for 1.0: static int read(Gauge g) { return g.read(); } */
    static byte[] mod() {
        ClassWriter w = JarPortStandinTest.start("n9/Mod");
        MethodVisitor read = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "(Ls9/Gauge;)I", null, null);
        read.visitCode();
        read.visitVarInsn(Opcodes.ALOAD, 0);
        read.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "s9/Gauge", "read", "()I", false);
        read.visitInsn(Opcodes.IRETURN);
        read.visitMaxs(0, 0);
        MethodVisitor zone = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "zone", "()Ljava/lang/Object;", null, null);
        zone.visitCode();
        zone.visitTypeInsn(Opcodes.NEW, "s9/Zone");
        zone.visitInsn(Opcodes.DUP);
        zone.visitMethodInsn(Opcodes.INVOKESPECIAL, "s9/Zone", "<init>", "()V", false);
        zone.visitInsn(Opcodes.ARETURN);
        zone.visitMaxs(0, 0);
        return JarPortStandinTest.end(w);
    }
}
