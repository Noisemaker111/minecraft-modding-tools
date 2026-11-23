import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Which game classes each of the mod's classes extends, for the fixes that depend on it. */
final class PortHierarchy {

    final Port port;

    PortHierarchy(Port port) {
        this.port = port;
    }

    Explain.Index supertypeIndex;

    /**
     * The supertypes (simple names) of every receiver type the log names in a "location:" line, read from the target
     * build's own classpath, one "Type Super1 Super2 ..." line each, for the typed fixer: a member renamed on Player is
     * renamed where javac looked it up on LocalPlayer. Null when the log names none.
     */
    Path supertypes(Path log) throws IOException {
        String text = Port.logText(log);
        Matcher located = Pattern.compile("location: (?:variable \\w+ of type |class |interface )([\\w.$]+)").matcher(text);
        Set<String> types = new TreeSet<>();
        while (located.find()) types.add(located.group(1).replaceAll("<.*", ""));
        Map<String, Set<String>> overriders = overridingClasses(text);
        if (types.isEmpty() && overriders.isEmpty()) return null;
        if (supertypeIndex == null) supertypeIndex = new Explain.Index(Port.classpath(port.model));
        StringBuilder out = new StringBuilder();
        for (String type : types) {
            List<String> found = supertypeIndex.resolve(type, List.of());
            if (found.isEmpty()) {
                // A class of the mod itself: its game supertypes, read through its own source.
                String simple = JavaText.simpleName(type.replace('/', '.'));
                Set<String> parents = new LinkedHashSet<>();
                for (Path file : Port.sources(port.build.project)) if (file.getFileName().toString().equals(simple + ".java")) {
                    sourceParents(Files.readString(file), simple, parents, 0);
                    break;
                }
                Set<String> supers = new LinkedHashSet<>();
                for (String parent : parents) {
                    supers.add(parent);
                    supers.addAll(gameSupertypes(parent));
                }
                if (supers.isEmpty()) continue;
                out.append(simple);
                for (String parent : supers) out.append(' ').append(JavaText.simpleName(parent.replace('/', '.')));
                out.append('\n');
                continue;
            }
            Set<String> supers = gameSupertypes(found.get(0));
            if (supers.isEmpty()) continue;
            out.append(JavaText.simpleName(type.replace('/', '.')));
            for (String parent : supers) out.append(' ').append(JavaText.simpleName(parent.replace('/', '.')));
            out.append('\n');
        }
        // A mod class whose @Override stopped overriding: its own game supertypes, so the fixer renames the method the
        // way the game renamed it on that supertype (Screen.render -> extractRenderState), not on some other owner.
        for (var entry : overriders.entrySet()) {
            Set<String> supers = new LinkedHashSet<>();
            for (String parent : entry.getValue()) {
                supers.add(parent.replace('/', '.'));
                supers.addAll(gameSupertypes(parent));
            }
            if (supers.isEmpty()) continue;
            out.append(entry.getKey());
            for (String parent : supers) out.append(' ').append(JavaText.simpleName(parent.replace('/', '.')));
            out.append('\n');
        }
        if (out.length() == 0) return null;
        Path file = log.resolveSibling(log.getFileName().toString().replaceFirst("\\.log$", "") + "-supertypes.txt");
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        return file;
    }

    /** Every supertype of a classpath class (internal names), nearest first. */
    Set<String> gameSupertypes(String internal) {
        Set<String> supers = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(internal));
        while (!queue.isEmpty()) {
            Explain.ClassInfo info = supertypeIndex.info(queue.poll());
            if (info == null) continue;
            List<String> next = new ArrayList<>(info.interfaces());
            if (info.superName() != null && !info.superName().equals("java/lang/Object")) next.add(0, info.superName());
            for (String parent : next) if (supers.add(parent)) queue.add(parent);
        }
        return supers;
    }

    /**
     * For each "method does not override" error, the enclosing class (simple name) and the classpath classes it extends
     * or implements (internal names). A parent the mod declares itself is followed through its own source, up to four
     * levels, until it reaches a class of the classpath.
     */
    Map<String, Set<String>> overridingClasses(String log) throws IOException {
        Map<String, Set<String>> out = new TreeMap<>();
        Matcher error = Pattern.compile("(?m)^\\s*" + Port.FILE + ":(\\d+): error: method does not override").matcher(log);
        while (error.find()) {
            Path file = Path.of(error.group(1));
            if (!file.isAbsolute()) file = port.build.project.root.resolve(file);
            if (!Files.isRegularFile(file)) continue;
            List<String> lines = Files.readAllLines(file);
            int line = Integer.parseInt(error.group(2));
            String enclosing = null;
            for (int back = Math.min(lines.size(), line) - 1; back >= 0 && enclosing == null; back--) {
                Matcher type = Pattern.compile("\\b(?:class|enum|record)\\s+(\\w+)").matcher(lines.get(back));
                if (type.find()) enclosing = type.group(1);
            }
            if (enclosing == null || out.containsKey(enclosing)) continue;
            if (supertypeIndex == null) supertypeIndex = new Explain.Index(Port.classpath(port.model));
            Set<String> parents = new LinkedHashSet<>();
            sourceParents(String.join("\n", lines), enclosing, parents, 0);
            if (!parents.isEmpty()) out.put(enclosing, parents);
        }
        return out;
    }

    /** The classpath classes a class declared in this source extends or implements, through the mod's own classes. */
    void sourceParents(String source, String type, Set<String> into, int depth) throws IOException {
        if (depth > 4) return;
        Matcher declared = Pattern.compile(
            "\\b(?:class|enum|record)\\s+" + Pattern.quote(type) + "\\b[^{]*?(?:\\bextends\\s+([\\w.]+)(?:<[^{]*?>)?)?\\s*(?:\\bimplements\\s+([^{]+))?\\{"
        ).matcher(source);
        if (!declared.find()) return;
        List<String> imports = new ArrayList<>();
        Matcher imp = Pattern.compile("(?m)^import\\s+([\\w.]+)\\s*;").matcher(source);
        while (imp.find()) imports.add(imp.group(1));
        List<String> named = new ArrayList<>();
        if (declared.group(1) != null) named.add(declared.group(1));
        if (declared.group(2) != null) for (String part : declared.group(2).replaceAll("<[^<>]*>", "").split(",")) {
            if (!part.isBlank()) named.add(part.trim());
        }
        for (String parent : named) {
            List<String> found = supertypeIndex.resolve(parent, imports);
            if (!found.isEmpty() && !found.get(0).startsWith("java/")) {
                into.add(found.get(0));
                continue;
            }
            // The mod's own class: read where it is declared.
            String simple = parent.substring(parent.lastIndexOf('.') + 1);
            for (Path other : Port.sources(port.build.project)) {
                if (!other.getFileName().toString().equals(simple + ".java")) continue;
                sourceParents(Files.readString(other), simple, into, depth + 1);
                break;
            }
        }
    }
}
