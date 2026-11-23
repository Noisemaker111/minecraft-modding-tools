import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * A Fabric bundle of one module per release (ViaFabric nests viafabric-mc1144 to -mc12111, each depending on its own
 * "minecraft"): Fabric Loader loads only the module whose range holds, so the port carries only what the new release
 * would load. A module for neither release is never loaded and holds nothing back; a module for the old release gives
 * way to the bundle's module for the new one; with none for the new one, the old release's module is ported to it.
 * Each module that must not be loaded calls a game method the new release dropped, so porting it shows as a break.
 */
final class JarPortBundleTest {

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        GameDiff.Snap a = new GameDiff.Snap("1.20.1"), b = new GameDiff.Snap("1.20.2");
        JarPortTest.snap(a, "g7/Api", 0, null, "kept()V", "gone()V");
        JarPortTest.snap(b, "g7/Api", 0, null, "kept()V");
        Path map = scratch.resolve("route-bundle.json");
        Files.writeString(map, "{}");

        // Past the bundle's newest module: the old release's module is ported, the older releases' are left out.
        Map<String, byte[]> past = new LinkedHashMap<>();
        past.put("fabric.mod.json", meta("bundle", "*", "META-INF/jars/mc119.jar", "META-INF/jars/mc120.jar", "META-INF/jars/common.jar"));
        past.put("META-INF/jars/mc119.jar", module("bundle-mc119", "1.19.x", "b7/mc119/Hook", "gone"));
        past.put("META-INF/jars/mc120.jar", module("bundle-mc120", ">=1.20 <1.20.2", "b7/mc120/Hook", "kept"));
        past.put("META-INF/jars/common.jar", module("bundle-common", null, "b7/common/Hook", "kept"));
        List<Map<String, Object>> results = new ArrayList<>();
        byte[] ported = new JarPort(a, b, map).port(JarPortTest.jar(past), "bundle.jar", results);
        Map<String, byte[]> out = JarPortTest.entries(ported);
        JarPortTest.expect(unlinked(results).isEmpty(), "a module for neither release is not linked against the new one: " + unlinked(results));
        JarPortTest.expect(minecraft(out.get("META-INF/jars/mc119.jar")).equals("1.19.x"),
            "a module for neither release keeps its range, so the loader still passes it over: " + minecraft(out.get("META-INF/jars/mc119.jar")));
        JarPortTest.expect(minecraft(out.get("META-INF/jars/mc120.jar")).equals("1.20.2"),
            "the old release's module, with none for the new one, is ported and asks for the new release: " + minecraft(out.get("META-INF/jars/mc120.jar")));
        List<String> skipped = skipped(results);
        JarPortTest.expect(skipped.size() == 1 && skipped.get(0).contains("mc119.jar") && skipped.get(0).contains("not loaded on 1.20.1 or 1.20.2"),
            "the module left out is reported with why: " + skipped);
        JarPortTest.expect(jars(results).containsAll(List.of("bundle.jar!mc120.jar", "bundle.jar!common.jar")) && !jars(results).contains("bundle.jar!mc119.jar"),
            "only the modules the loader loads are ported: " + jars(results));
        Set<String> loadedClasses = names(ModNames.Back.classesIn(ported, "1.20.2"));
        JarPortTest.expect(loadedClasses.contains("b7/mc120/Hook") && loadedClasses.contains("b7/common/Hook") && !loadedClasses.contains("b7/mc119/Hook"),
            "the Fabric hand-back reads the classes of the modules loaded on the release only: " + loadedClasses);

        // A bundle with a module for the new release: the old release's module gives way to it.
        Map<String, byte[]> covered = new LinkedHashMap<>();
        covered.put("fabric.mod.json", meta("bundle", "*", "META-INF/jars/mc1201.jar", "META-INF/jars/mc1202.jar"));
        covered.put("META-INF/jars/mc1201.jar", module("bundle-mc1201", List.of("1.20", "1.20.1"), "b7/mc1201/Hook", "gone"));
        covered.put("META-INF/jars/mc1202.jar", module("bundle-mc1202", "1.20.2", "b7/mc1202/Hook", "kept"));
        results.clear();
        out = JarPortTest.entries(new JarPort(a, b, map).port(JarPortTest.jar(covered), "covered.jar", results));
        JarPortTest.expect(unlinked(results).isEmpty(), "the old release's module that gives way is not linked: " + unlinked(results));
        JarPortTest.expect(minecraft(out.get("META-INF/jars/mc1201.jar")).equals("[\"1.20\",\"1.20.1\"]"),
            "the module that gives way keeps its range, so the new release does not load two: " + minecraft(out.get("META-INF/jars/mc1201.jar")));
        skipped = skipped(results);
        JarPortTest.expect(skipped.size() == 1 && skipped.get(0).contains("mc1201.jar") && skipped.get(0).contains("runs mc1202.jar on 1.20.2 instead"),
            "the module that gives way is reported with the one that runs instead: " + skipped);
        JarPortTest.expect(jars(results).contains("covered.jar!mc1202.jar"), "the new release's module is carried with the port: " + jars(results));

        // A port of two steps chooses for the whole port: the last release's module goes from the first step, and the
        // first release's module, which no step after the first would load, is never carried.
        GameDiff.Snap c = new GameDiff.Snap("1.20.3");
        JarPortTest.snap(c, "g7/Api", 0, null, "kept()V");
        Map<String, byte[]> steps = new LinkedHashMap<>();
        steps.put("fabric.mod.json", meta("bundle", "*", "META-INF/jars/mc1201.jar", "META-INF/jars/mc1203.jar"));
        steps.put("META-INF/jars/mc1201.jar", module("bundle-mc1201", "1.20.1", "b7/mc1201/Hook", "gone"));
        steps.put("META-INF/jars/mc1203.jar", module("bundle-mc1203", "1.20.3", "b7/mc1203/Hook", "kept"));
        Map<String, Map<String, String>> decided = new HashMap<>();
        byte[] step = JarPortTest.jar(steps);
        results.clear();
        for (GameDiff.Snap[] hop : new GameDiff.Snap[][] { { a, b }, { b, c } }) {
            JarPort port = new JarPort(hop[0], hop[1], map);
            port.origin = "1.20.1";
            port.dataStep = new DataIds.Step(Map.of(), Map.of(), null, null, false); // no data in this mod: no reports to read
            port.portTo = "1.20.3";
            port.decided = decided;
            step = port.port(step, "steps.jar", results);
        }
        out = JarPortTest.entries(step);
        JarPortTest.expect(unlinked(results).isEmpty() && minecraft(out.get("META-INF/jars/mc1201.jar")).equals("1.20.1")
            && minecraft(out.get("META-INF/jars/mc1203.jar")).equals("1.20.3") && jars(results).stream().filter("steps.jar!mc1203.jar"::equals).count() == 2,
            "every step of a port carries the last release's module and none carries the first's: " + jars(results) + " " + unlinked(results)
                + " " + minecraft(out.get("META-INF/jars/mc1201.jar")) + " " + minecraft(out.get("META-INF/jars/mc1203.jar")));

        // A plan of several targets shares one trunk of steps (JarPort.portAll); a bundle whose carried modules depend on
        // the release the port ends at cannot share it, as its first step already differs, while a jar of no bundle can.
        Path bundleJar = Files.write(scratch.resolve("steps.jar"), JarPortTest.jar(steps)), plainJar = Files.write(scratch.resolve("plain.jar"), JarPortTest.jar(Map.of(
            "fabric.mod.json", meta("plain", "*", "META-INF/jars/common.jar"), "META-INF/jars/common.jar", module("plain-common", null, "b7/plain/Hook", "kept"))));
        List<JarPort.Target> plan = List.of(new JarPort.Target(List.of("1.20.1", "1.20.2"), List.of("m"), map, ""),
            new JarPort.Target(List.of("1.20.1", "1.20.2", "1.20.3"), List.of("m", "m"), map, ""));
        JarPortTest.expect(!JarPort.sameChoices(bundleJar, plan, "fabric"), "a bundle carrying another module to each target is ported to each on its own");
        JarPortTest.expect(JarPort.sameChoices(plainJar, plan, "fabric") && JarPort.sameChoices(bundleJar, plan, "neoforge"),
            "a jar that carries the same to every target, and every jar on NeoForge, shares the trunk");

        // NeoForge's loader picks no module by its "minecraft": every nested jar is ported, as before.
        GameDiff.Snap na = new GameDiff.Snap("1.20.1"), nb = new GameDiff.Snap("1.20.2");
        nb.loaderVersion = "20.2.1";
        JarPortTest.snap(na, "g7/Api", 0, null, "kept()V", "gone()V");
        JarPortTest.snap(nb, "g7/Api", 0, null, "kept()V");
        results.clear();
        new JarPort(na, nb, map).port(JarPortTest.jar(past), "neo.jar", results);
        JarPortTest.expect(skipped(results).isEmpty() && unlinked(results).stream().anyMatch(u -> u.contains("g7.Api.gone")),
            "on NeoForge no module is left out by its Fabric range: " + skipped(results) + " " + unlinked(results));
        JarPortTest.finish("JarPortBundleTest");
    }

    static byte[] meta(String id, Object minecraft, String... jars) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schemaVersion", 1);
        meta.put("id", id);
        meta.put("version", "1");
        if (minecraft != null) {
            meta.put("depends", new LinkedHashMap<>(Map.of("minecraft", minecraft)));
        }
        List<Object> list = new ArrayList<>();
        for (String j : jars) {
            list.add(Map.of("file", j));
        }
        if (!list.isEmpty()) {
            meta.put("jars", list);
        }
        return Json.write(meta).getBytes(StandardCharsets.UTF_8);
    }

    /** A nested module whose one class calls g7.Api.called(). */
    static byte[] module(String id, Object minecraft, String cls, String called) throws Exception {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, cls, null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "use", "(Lg7/Api;)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g7/Api", called, "()V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return JarPortTest.jar(Map.of("fabric.mod.json", meta(id, minecraft), cls + ".class", w.toByteArray()));
    }

    static String minecraft(byte[] jar) throws Exception {
        Object range = JarPort.fabricMinecraft(jar);
        return range instanceof String s ? s : Json.write(range);
    }

    static List<String> unlinked(List<Map<String, Object>> results) {
        return results.stream().flatMap(r -> Json.array(r.get("unlinked")).stream()).map(String::valueOf).toList();
    }

    static List<String> skipped(List<Map<String, Object>> results) {
        return results.stream().flatMap(r -> Json.array(r.get("skipped")).stream()).map(String::valueOf).toList();
    }

    static List<String> jars(List<Map<String, Object>> results) {
        return results.stream().map(r -> String.valueOf(r.get("jar"))).toList();
    }

    static Set<String> names(List<byte[]> classes) {
        Set<String> out = new TreeSet<>();
        for (byte[] c : classes) {
            out.add(new ClassReader(c).getClassName());
        }
        return out;
    }
}
