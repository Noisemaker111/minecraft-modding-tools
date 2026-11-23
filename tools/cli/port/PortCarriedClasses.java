import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Game classes the update removed or closed that the mod still uses: carried into the mod's own source. */
final class PortCarriedClasses {

    final Port port;

    PortCarriedClasses(Port port) {
        this.port = port;
    }

    /**
     * A class javac cannot find in a file that does not import it (a rule wrote RenderLayer::getGuiTextured, a class moved
     * package) is imported when exactly one class of the target game or loader has that simple name. Classes of the mod
     * itself, and names two game classes share, are left to the compiler. Returns the files changed.
     */
    int importMissingClasses(Path log) throws Exception {
        String errors = Port.logText(log);
        Matcher reported = Pattern.compile(
            "(?m)^\\s*" + Port.FILE + ":[0-9]+: error: cannot find symbol\\R(?:.*\\R){0,2}?\\s*symbol:\\s+(?:class|variable) ([A-Z]\\w*)\\s*$"
        ).matcher(errors);
        Map<Path, Set<String>> wanted = new LinkedHashMap<>();
        while (reported.find()) {
            Path file = Path.of(reported.group(1));
            if (!file.isAbsolute()) file = port.build.project.root.resolve(file);
            file = file.normalize();
            if (file.startsWith(port.build.project.root) && Files.isRegularFile(file)) wanted
                .computeIfAbsent(file, f -> new LinkedHashSet<>())
                .add(reported.group(2));
        }
        if (wanted.isEmpty()) return 0;
        if (port.hierarchy.supertypeIndex == null) port.hierarchy.supertypeIndex = new Explain.Index(Port.classpath(port.model));
        int changed = 0;
        for (var entry : wanted.entrySet()) {
            String text = Files.readString(entry.getKey()), after = text;
            for (String simple : entry.getValue()) {
                if (Pattern.compile("(?m)^import\\s+[\\w.]+\\." + simple + "\\s*;").matcher(after).find()) continue;
                if (Pattern.compile("\\b(?:class|interface|enum|record)\\s+" + simple + "\\b").matcher(after).find()) continue;
                List<String> game = new ArrayList<>();
                for (String name : port.hierarchy.supertypeIndex.bySimple.getOrDefault(simple, List.of())) if (
                    !name.contains("$") &&
                    (name.startsWith("net/minecraft/") || name.startsWith("com/mojang/") || name.startsWith("net/fabricmc/") || name.startsWith("net/neoforged/"))
                ) game.add(name);
                if (game.size() != 1) continue;
                after = JavaText.addImport(after, game.get(0).replace('/', '.'));
            }
            if (!after.equals(text)) {
                Files.writeString(entry.getKey(), after);
                changed++;
            }
        }
        if (changed > 0) port.rulesApplied.putIfAbsent("import-missing-game-class", List.of());
        return changed;
    }

    /**
     * A game class the target removed (1.21.2's BlockEntityType.Builder) is carried into the mod: its source in the old
     * game, from the decompiled game the old build set up, becomes a class of the mod's own package, and every use
     * follows. The next repair round ports the carried code like the mod's own, with the same map; javac decides whether
     * it stands. Only a top-level class or a static nested one is carried.
     */
    int carryRemovedClasses(Path log, String from) throws Exception {
        String errors = Port.logText(log);
        Matcher reported = Pattern.compile(
            "(?m)^\\s*" + Port.FILE + ":[0-9]+: error: cannot find symbol\\R(?:.*\\R){0,2}?\\s*symbol:\\s+class ([A-Z]\\w*)\\R\\s*location:\\s+(class|package) ([\\w.]+)"
        ).matcher(errors);
        int carried = 0;
        Set<String> done = new HashSet<>();
        while (reported.find()) {
            Path file = Path.of(reported.group(1));
            if (!file.isAbsolute()) file = port.build.project.root.resolve(file);
            file = file.normalize();
            if (!file.startsWith(port.build.project.root) || !Files.isRegularFile(file)) continue;
            String text = Files.readString(file), simple = reported.group(2), outer = null, pkg;
            if (reported.group(3).equals("package")) pkg = reported.group(4);
            else {
                String owner = reported.group(4);
                Matcher imported = Pattern.compile("(?m)^import\\s+([\\w.]+)\\." + Pattern.quote(owner) + "\\s*;").matcher(text);
                if (owner.contains(".")) {
                    pkg = owner.substring(0, owner.lastIndexOf('.'));
                    outer = owner.substring(owner.lastIndexOf('.') + 1);
                } else if (imported.find()) {
                    pkg = imported.group(1);
                    outer = owner;
                } else continue;
            }
            if (!pkg.startsWith("net.minecraft") && !pkg.startsWith("com.mojang")) continue;
            // Only a class the game map records as removed is carried; one it moves or replaces is the map's own step.
            if (!port.maps.mappedClasses().contains(pkg + "." + (outer == null ? simple : outer + "$" + simple))) continue;
            Matcher declared =Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;").matcher(text);
            if (!declared.find()) continue;
            String[] parts = declared.group(1).split("\\.");
            String home = String.join(".", Arrays.copyOf(parts, Math.min(3, parts.length))) + ".mmt.carried";
            String name = outer == null ? simple : outer + simple;
            Path root = file;
            for (int i = 0; i < parts.length + 1 && root != null; i++) root = root.getParent();
            if (root == null) continue;
            Path target = root.resolve(home.replace('.', '/')).resolve(name + ".java");
            if (done.add(target.toString()) && !Files.isRegularFile(target)) {
                String source = oldGameSource(pkg.replace('.', '/') + "/" + (outer == null ? simple : outer) + ".java", from);
                if (source == null) continue;
                String body = outer == null ? topLevel(source, simple) : nestedDeclaration(source, simple);
                if (body == null) continue;
                StringBuilder imports = new StringBuilder();
                Matcher imp = Pattern.compile("(?m)^import\\s+[^;]+;").matcher(source);
                while (imp.find()) imports.append(imp.group()).append('\n');
                if (outer != null) imports.append("import ").append(pkg).append('.').append(outer).append(";\nimport ").append(pkg).append('.').append(outer).append(".*;\n");
                else imports.append("import ").append(pkg).append(".*;\n");
                body = body.replaceAll("\\b" + (outer == null ? "" : Pattern.quote(outer) + "\\.") + Pattern.quote(simple) + "\\b", name);
                if (outer != null) body = body.replaceAll("(?<![\\w.])" + Pattern.quote(simple) + "\\b", name);
                body = body.replaceFirst("^(\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*)(?:(?:public|protected|private|static)\\s+)*", "$1public ");
                String origin = pkg + "." + (outer == null ? simple : outer + "." + simple);
                String carriedSource = "package " + home + ";\n\n" + imports + "\n/** Carried by mmt port from Minecraft " + from + " (" + origin
                    + "), which the target removed. */\n" + body.strip() + "\n";
                port.journal.track(target);
                Files.createDirectories(target.getParent());
                Files.writeString(target, carriedSource);
                carried++;
            }
            if (!Files.isRegularFile(target)) continue;
            // Every use in the mod follows, not only the ones javac reported this round.
            for (Path user : Port.sources(port.build.project)) {
                if (user.equals(target)) continue;
                String before = Files.readString(user);
                String uses = outer == null
                    ? before.replaceAll("(?m)^import\\s+" + Pattern.quote(pkg + "." + simple) + "\\s*;", "import " + home + "." + name + ";")
                    : before.replaceAll("\\b" + Pattern.quote(outer) + "\\." + Pattern.quote(simple) + "\\b", name);
                if (uses.equals(before)) continue;
                if (outer != null && !uses.contains("package " + home + ";")) uses = JavaText.addImport(uses, home + "." + name);
                Files.writeString(user, uses);
            }
        }
        carried += carryInaccessible(errors, from);
        if (carried > 0) port.rulesApplied.computeIfAbsent("carry-removed-game-class", k -> new ArrayList<>()).add(carried + " class(es)");
        return carried;
    }

    /**
     * Carried code may name a game type only the game's own package can use (BlockEntityType.BlockEntitySupplier, which
     * the removed Builder used from inside): that functional interface is carried too, public, and where a carried value
     * of it goes back to the game, it is passed as a method reference to its one method (factory::create), which the
     * game's own interface accepts. Only inside carried classes.
     */
    int carryInaccessible(String errors, String from) throws Exception {
        int carried = 0;
        Matcher hidden = Pattern.compile("(?m)^\\s*" + Port.FILE + ":[0-9]+: error: (\\w+) is not public in ([\\w.]+); cannot be accessed from outside package").matcher(errors);
        Set<String> done = new HashSet<>();
        while (hidden.find()) {
            Path file = Path.of(hidden.group(1)).normalize();
            String simple = hidden.group(2), owner = hidden.group(3);
            if (!file.toString().replace('\\', '/').contains("/mmt/carried/") || !Files.isRegularFile(file)) continue;
            String text = Files.readString(file);
            String outer = owner.contains(".") ? owner.substring(owner.lastIndexOf('.') + 1) : owner;
            Matcher imported = Pattern.compile("(?m)^import\\s+([\\w.]+)\\." + Pattern.quote(outer) + "\\s*;").matcher(text);
            String pkg = owner.contains(".") ? owner.substring(0, owner.lastIndexOf('.')) : imported.find() ? imported.group(1) : null;
            if (pkg == null) continue;
            String home = text.replaceFirst("(?s)^\\s*package\\s+([\\w.]+);.*", "$1"), name = outer + simple;
            Path target = file.resolveSibling(name + ".java");
            if (done.add(name) && !Files.isRegularFile(target)) {
                String source = oldGameSource(pkg.replace('.', '/') + "/" + outer + ".java", from);
                String body = source == null ? null : nestedDeclaration(source, simple);
                if (body == null || !body.matches("(?s)[^{]*\\binterface\\b.*")) continue;
                body = body.replaceAll("(?<![\\w.])" + Pattern.quote(simple) + "\\b", name);
                body = body.replaceFirst("^(\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*)(?:(?:public|protected|private|static)\\s+)*", "$1public ");
                StringBuilder imports = new StringBuilder();
                Matcher imp = Pattern.compile("(?m)^import\\s+[^;]+;").matcher(source);
                while (imp.find()) imports.append(imp.group()).append('\n');
                port.journal.track(target);
                String header = "package " + home + ";\n\n" + imports + "import " + pkg + "." + outer + ";\n\n";
                String why = "/** Carried by mmt port from Minecraft " + from + " (" + pkg + "." + outer + "." + simple + "), which only the game's own package can use. */\n";
                Files.writeString(target, header + why + body.strip() + "\n");
                carried++;
            }
            String uses = text.replaceAll("\\b" + Pattern.quote(outer) + "\\." + Pattern.quote(simple) + "\\b", name);
            // A carried value of it handed to a game constructor goes as a reference to its one method, which the game's
            // own interface accepts (new BlockEntityType<>(this.factory::create, ..)).
            Pattern abstractMethod = Pattern.compile("(?m)^\\s*(?:@\\w+\\s+)*(?:public\\s+)?(?:abstract\\s+)?[\\w<>?,. \\[\\]]+\\s+(\\w+)\\s*\\([^)]*\\)\\s*;");
            Matcher method = Files.isRegularFile(target) ? abstractMethod.matcher(Files.readString(target)) : null;
            if (method != null && method.find()) {
                Set<String> values = new HashSet<>();
                Matcher typed = Pattern.compile("\\b" + Pattern.quote(name) + "(?:<[^;()]*?>)?\\s+(\\w+)\\s*[;,)=]").matcher(uses);
                while (typed.find()) values.add(typed.group(1));
                Matcher call = Pattern.compile("\\bnew\\s+(\\w+)\\s*(?:<[^()]*>)?\\s*(?:/\\*mmt-diamond\\*/)?\\(").matcher(uses);
                StringBuilder out = new StringBuilder();
                int last = 0;
                while (call.find()) {
                    if (Files.isRegularFile(file.resolveSibling(call.group(1) + ".java"))) continue;
                    int open = call.end() - 1, close = JavaText.closeParen(uses, open);
                    if (close < 0) continue;
                    String args = uses.substring(open + 1, close);
                    String adapted = args;
                    for (String v : values) adapted = adapted.replaceAll("(?<![\\w.:])((?:this\\.)?" + Pattern.quote(v) + ")(?![\\w(:])", "$1::" + method.group(1));
                    if (adapted.equals(args)) continue;
                    out.append(uses, last, open + 1).append(adapted);
                    last = close;
                }
                out.append(uses.substring(last));
                uses = out.toString();
            }
            if (!uses.equals(text)) Files.writeString(file, uses);
        }
        // A carried functional value passed where the game's interface of that name is wanted: pass its method instead.
        Matcher mismatch = Pattern.compile("(?m)^\\s*" + Port.FILE + ":([0-9]+): error: [^\\n]*?(\\w+)(?:<[^>\\n]*>)? cannot be converted to [^\\n]*\\n([^\\n]*)\\n([ \\t]*)\\^").matcher(errors);
        while (mismatch.find()) {
            Path file = Path.of(mismatch.group(1)).normalize();
            if (!file.toString().replace('\\', '/').contains("/mmt/carried/")) continue;
            Path iface = file.resolveSibling(mismatch.group(3) + ".java");
            if (!Files.isRegularFile(iface)) continue;
            Matcher method = Pattern.compile("(?m)^\\s*(?:@\\w+\\s+)*(?:public\\s+)?(?:abstract\\s+)?[\\w<>?,. \\[\\]]+\\s+(\\w+)\\s*\\([^)]*\\)\\s*;").matcher(Files.readString(iface));
            if (!method.find()) continue;
            List<String> lines = new ArrayList<>(Arrays.asList(Files.readString(file).split("\n", -1)));
            int at = Integer.parseInt(mismatch.group(2)) - 1, column = mismatch.group(5).length();
            if (at >= lines.size() || column >= lines.get(at).length()) continue;
            String line = lines.get(at);
            Matcher value = Pattern.compile("[\\w.]+").matcher(line);
            if (!value.find(column) || value.start() != column || line.startsWith("::", value.end())) continue;
            lines.set(at, line.substring(0, value.end()) + "::" + method.group(1) + line.substring(value.end()));
            Files.writeString(file, String.join("\n", lines));
            carried++;
        }
        return carried;
    }

    /**
     * javac names no required and found arguments for a constructor call written with a diamond (new BlockEntityType<>(..)
     * after 1.21.2 dropped a parameter: "cannot infer type arguments ... argument lists differ in length"), so the typed
     * repair has nothing to match. The call loses its diamond for a round, javac then lists both, and the repair that
     * drops or converts arguments acts; the diamond comes back once that line compiles.
     */
    int undiamond(Path log) throws Exception {
        Matcher m = Pattern.compile("(?m)^\\s*" + Port.FILE + ":([0-9]+): error: (?:incompatible types: )?cannot infer type arguments for (\\w+)<>\\n(?:[^\\n]*\\n){0,3}?\\s*reason: (?:cannot infer type-variable\\(s\\) [^\\n]*\\n\\s*\\((?:actual and formal argument lists differ in length|argument mismatch[^\\n]*)\\)|actual and formal)").matcher(Port.logText(log));
        int changed = 0;
        while (m.find()) {
            Path file = Path.of(m.group(1)).normalize();
            if (!file.startsWith(port.build.project.root) || !Files.isRegularFile(file)) continue;
            List<String> lines = new ArrayList<>(Arrays.asList(Files.readString(file).split("\n", -1)));
            int at = Integer.parseInt(m.group(2)) - 1;
            if (at >= lines.size()) continue;
            String line = lines.get(at), raw = line.replaceFirst("\\bnew\\s+" + Pattern.quote(m.group(3)) + "<>\\(", "new " + m.group(3) + "/*mmt-diamond*/(");
            if (raw.equals(line)) continue;
            lines.set(at, raw);
            Files.writeString(file, String.join("\n", lines));
            changed++;
        }
        // A line that compiles again gets its diamond back.
        String errors = Port.logText(log);
        for (Path source : Port.sources(port.build.project)) {
            if (!source.toString().endsWith(".java")) continue;
            String text = Files.readString(source);
            if (!text.contains("/*mmt-diamond*/")) continue;
            List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
            boolean restored = false;
            for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains("/*mmt-diamond*/") && !errors.contains(source.toString() + ":" + (i + 1) + ":")) {
                lines.set(i, lines.get(i).replace("/*mmt-diamond*/", "<>"));
                restored = true;
            }
            if (restored) Files.writeString(source, String.join("\n", lines));
        }
        return changed;
    }

    /** The old game's source of one file, from a decompiled game jar the build set up for `from` (ModDevGradle, Loom). */
    String oldGameSource(String entry, String from) throws IOException {
        List<Path> jars = new ArrayList<>();
        String neo = "neoforge-" + TargetNeoForge.neoForgePrefix(from);
        Files.walkFileTree(port.build.project.root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
                return (n.startsWith(".") || n.equals("src")) && !dir.equals(port.build.project.root) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                String n = file.getFileName().toString();
                if (n.endsWith("-sources.jar") && file.toString().contains("moddev") && (n.startsWith("vanilla-" + from + "-") || n.startsWith(neo))) jars.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        for (Path jar : jars) try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
            var e = zip.getEntry(entry);
            if (e != null) return new String(zip.getInputStream(e).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        return null;
    }

    /** A static nested type's declaration (annotations to closing brace) in a class's source, or null. */
    static String nestedDeclaration(String source, String simple) {
        Matcher head = Pattern.compile("(?m)^([ \\t]*)((?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:(?:public|protected|private|static|final|abstract)\\s+)*)(class|interface|enum|record)\\s+" + Pattern.quote(simple) + "\\b[^{]*\\{").matcher(source);
        if (!head.find()) return null;
        String modifiers = head.group(2);
        if (head.group(3).equals("class") && !modifiers.contains("static")) return null;
        int depth = 0;
        for (int i = head.end() - 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(head.start(), i + 1);
        }
        return null;
    }

    /** A top-level type's declaration in its own file, or null. */
    static String topLevel(String source, String simple) {
        Matcher head = Pattern.compile("(?m)^((?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:(?:public|final|abstract)\\s+)*)(class|interface|enum|record)\\s+" + Pattern.quote(simple) + "\\b").matcher(source);
        if (!head.find()) return null;
        int end = source.lastIndexOf('}');
        return end < head.start() ? null : source.substring(head.start(), end + 1);
    }
}
