import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** The mod's access transformer in source: moved to the new names, and widened for what the port needs opened. */
final class PortAccess {

    final Port port;

    PortAccess(Port port) {
        this.port = port;
    }

    /**
     * A game member the mod reached through its access transformer that the target game moved, renamed or re-typed is
     * private again: the old entry names a member that is gone. javac names the member and the class it lives in now
     * ("X(args) has private access in Y", "X is not public in Y"), so the entry is written for the member as the target
     * game has it, its descriptor read from the target's own class files. NeoForge mods only (accesstransformer.cfg);
     * an entry already there is not written twice. Returns the lines added.
     */
    List<String> repairAccess(Path log) throws Exception {
        Set<String> loaders = new TreeSet<>();
        for (Object p : Json.array(port.model.get("projects"))) loaders.addAll(Support.loaders(Json.object(p)));
        if (!loaders.contains("neoforge")) return List.of();
        String errors = Port.logText(log);
        Matcher denied = Pattern.compile(
            "(?m)^\\s*" +
            Port.FILE +
            ":[0-9]+: error: (\\w+)(?:\\(([^)]*)\\))? (?:has private access in|is not public in) ([\\w.$]+)"
        ).matcher(errors);
        Map<String, String[]> wanted = new LinkedHashMap<>();
        while (denied.find()) wanted.putIfAbsent(
            denied.group(4) + "." + denied.group(2) + "(" + denied.group(3) + ")",
            new String[] { denied.group(2), denied.group(3), denied.group(4) }
        );
        // An overloaded call names the private candidate in its list: "(m(args) has private access in Y)"; a call to
        // the one candidate gives it as javac's reason ("reason: m(args) has private access in Y").
        Matcher candidate = Pattern.compile("(?:\\(|reason: )(\\w+)\\(([^)]*)\\) has private access in ([\\w.$]+)").matcher(errors);
        while (candidate.find()) wanted.putIfAbsent(
            candidate.group(3) + "." + candidate.group(1) + "(" + candidate.group(2) + ")",
            new String[] { candidate.group(1), candidate.group(2), candidate.group(3) }
        );
        if (wanted.isEmpty()) return List.of();
        Path at = accessTransformer();
        if (at == null || !Files.isRegularFile(at)) return List.of();
        Explain.Index index = new Explain.Index(Port.classpath(port.model));
        String text = Files.readString(at, StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>();
        for (String[] member : wanted.values()) {
            String owner = null;
            for (String found : index.resolve(member[2], List.of())) if (
                found.startsWith("net/minecraft/") || found.startsWith("com/mojang/")
            ) {
                owner = found;
                break;
            }
            if (owner == null) continue;
            Explain.ClassInfo info = index.info(owner);
            if (info == null) continue;
            String entry = null;
            for (Explain.Member m : info.members()) {
                if (!m.name().equals(member[0]) || m.method() != (member[1] != null)) continue;
                // javac writes type arguments (Function<ResourceLocation,RenderType>); a descriptor has none.
                if (m.method() && !parameters(m.descriptor()).equals(withoutTypeArguments(member[1]).replace(" ", ""))) continue;
                entry = "public " + owner.replace('/', '.') + " " + m.name() + (m.method() ? m.descriptor() : "");
                break;
            }
            if (entry == null || text.contains(entry) || lines.contains(entry)) continue;
            lines.add(entry);
        }
        if (lines.isEmpty()) return lines;
        port.journal.track(at);
        String added =
            (text.endsWith("\n") ? "" : "\n") +
            "\n# Written by mmt port: members the target game made private again, as it has them now\n" +
            String.join("\n", lines) +
            "\n";
        Files.writeString(at, text + added, StandardCharsets.UTF_8);
        port.rulesApplied.putIfAbsent("access-transformer-repair", List.of());
        return lines;
    }

    /** Java type text without its type arguments: Function<ResourceLocation,RenderType>,int -> Function,int. */
    static String withoutTypeArguments(String types) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        for (char c : types.toCharArray()) {
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (depth == 0) out.append(c);
        }
        return out.toString();
    }

    /** A descriptor's parameter types written the way javac's messages write them: (ILjava/lang/String;)V -> int,String. */
    static String parameters(String descriptor) {
        List<String> types = new ArrayList<>();
        int[] at = { 1 };
        while (descriptor.charAt(at[0]) != ')') types.add(Explain.Index.type(descriptor, at));
        return String.join(",", types);
    }

    /** The NeoForge access transformer the build applies: NeoGradle's accessTransformers file, or META-INF/accesstransformer.cfg. */
    /**
     * The access transformer follows the game before the first build: an entry for a class, field or method the map moves,
     * renames or re-types names it as the target has it (descriptors included). What the map cannot place stays, for the
     * build to name.
     */
    int portAccessTransformer(Path mapFile) throws Exception {
        Path at = accessTransformer();
        if (at == null || mapFile == null || !Files.isRegularFile(at) || !Files.isRegularFile(mapFile)) return 0;
        var map = Json.read(mapFile);
        Map<String, String> classes = new HashMap<>();
        for (Object o : Json.array(map.get("classes"))) classes.put(Json.text(Json.object(o), "from", ""), Json.text(Json.object(o), "to", ""));
        Map<String, String[]> members = new HashMap<>(); // owner name desc -> {owner, name, desc}
        for (String list : List.of("methods", "methodsMoved", "signatures", "fields")) for (Object o : Json.array(map.get(list))) {
            var m = Json.object(o);
            String owner = Json.text(m, "owner", ""), desc = Json.text(m, "desc", "");
            members.putIfAbsent(owner + " " + Json.text(m, "from", "") + (list.equals("fields") ? "" : desc), new String[] {
                Json.text(m, "newOwner", classes.getOrDefault(owner, owner)), Json.text(m, "to", ""), list.equals("fields") ? "" : Json.text(m, "newDesc", desc)
            });
        }
        if (classes.isEmpty() && members.isEmpty()) return 0;
        List<String> lines = new ArrayList<>(Arrays.asList(Files.readString(at, StandardCharsets.UTF_8).split("\n", -1)));
        int changed = followMap(lines, classes, members);
        if (changed == 0) return 0;
        port.journal.track(at);
        Files.writeString(at, String.join("\n", lines), StandardCharsets.UTF_8);
        port.rulesApplied.computeIfAbsent("access-transformer-follows-map", k -> new ArrayList<>()).add(changed + " entr(y/ies)");
        return changed;
    }

    /** Access transformer lines rewritten for the map's class moves and member renames ({owner, name, desc} by "owner namedesc"). */
    static int followMap(List<String> lines, Map<String, String> classes, Map<String, String[]> members) {
        Pattern entry = Pattern.compile("^(\\s*(?:public|protected|private|default)(?:[-+]f)?\\s+)([\\w.$]+)(?:(\\s+)([\\w$<>]+)(\\([^)\\s]*\\)\\S+)?)?(.*)$");
        int changed = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher m = entry.matcher(line.replace("\r", ""));
            if (!m.find()) continue;
            String owner = m.group(2), name = m.group(4), desc = m.group(5) == null ? "" : m.group(5);
            String[] to = name == null ? null : members.get(owner + " " + name + desc);
            String newOwner = to != null ? to[0] : classes.getOrDefault(owner, owner), newName = to != null ? to[1] : name, newDesc = to != null ? to[2] : desc;
            // Classes inside a descriptor follow their moves too.
            if (!newDesc.isEmpty()) {
                Matcher type = Pattern.compile("L([\\w/$]+);").matcher(newDesc);
                StringBuilder out = new StringBuilder();
                while (type.find()) {
                    String dotted = type.group(1).replace('/', '.');
                    type.appendReplacement(out, Matcher.quoteReplacement("L" + classes.getOrDefault(dotted, dotted).replace('.', '/') + ";"));
                }
                type.appendTail(out);
                newDesc = out.toString();
            }
            String rewritten = m.group(1) + newOwner + (name == null ? "" : m.group(3) + newName + newDesc) + m.group(6) + (line.endsWith("\r") ? "\r" : "");
            if (!rewritten.equals(line)) {
                lines.set(i, rewritten);
                changed++;
            }
        }
        return changed;
    }

    /**
     * Entries the build says name nothing in the target game (NeoForge's validation: "access-transformer:missing-target:
     * The target X does not exist. @ file:line") are commented out with why, so the build goes on and javac names the
     * code that still needs them.
     */
    int dropMissingAccessTargets(Path log) throws Exception {
        Matcher missing = Pattern.compile("access-transformer:missing-target: The target (.+?) does not exist\\. @ (.+?):(\\d+)").matcher(Port.logText(log));
        Map<Path, Map<Integer, String>> byFile = new LinkedHashMap<>();
        while (missing.find()) byFile.computeIfAbsent(Path.of(missing.group(2).strip()), f -> new TreeMap<>()).put(Integer.parseInt(missing.group(3)), missing.group(1));
        int dropped = 0;
        for (var e : byFile.entrySet()) {
            Path at = e.getKey();
            if (!Files.isRegularFile(at) || !at.toAbsolutePath().normalize().startsWith(port.build.project.root)) continue;
            List<String> lines = new ArrayList<>(Arrays.asList(Files.readString(at, StandardCharsets.UTF_8).split("\n", -1)));
            for (var line : e.getValue().entrySet()) {
                int i = line.getKey() - 1;
                if (i < 0 || i >= lines.size() || lines.get(i).stripLeading().startsWith("#")) continue;
                lines.set(i, "# mmt port: the target game has no " + line.getValue() + "\n# " + lines.get(i));
                dropped++;
            }
            port.journal.track(at);
            Files.writeString(at, String.join("\n", lines), StandardCharsets.UTF_8);
        }
        if (dropped > 0) port.rulesApplied.computeIfAbsent("access-transformer-missing-target", k -> new ArrayList<>()).add(dropped + " entr(y/ies)");
        return dropped;
    }

    Path accessTransformer() throws IOException {
        for (String script : List.of("build.gradle", "build.gradle.kts")) {
            Path file = port.build.project.root.resolve(script);
            if (!Files.isRegularFile(file)) continue;
            Matcher configured = Pattern.compile(
                "accessTransformers?[^\\n]*?file\\s*\\(?\\s*(?:rootProject\\.|project\\.)?file\\(\\s*['\"]([^'\"]+)['\"]"
            ).matcher(Files.readString(file));
            if (configured.find()) return port.build.project.root.resolve(configured.group(1)).normalize();
        }
        Path fallback = port.build.project.root.resolve("src/main/resources/META-INF/accesstransformer.cfg");
        return Files.isRegularFile(fallback) ? fallback : null;
    }
}
