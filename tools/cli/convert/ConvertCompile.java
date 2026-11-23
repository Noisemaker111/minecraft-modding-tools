import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** The converted project compiled, and the repairs made from what the compiler says. */
final class ConvertCompile {

    final Convert convert;

    ConvertCompile(Convert convert) {
        this.convert = convert;
    }

    // ---- check and report --------------------------------------------------------------------------------

    void compile(Map<String, Object> result) throws Exception {
        Build fresh = new Build(convert.project, convert.build.harness, "", convert.build.report);
        Map<String, Object> model = null;
        // Build logic written for Fabric's Loom that no rule moves (a third-party plugin that needs Loom, a shared remote
        // script that applies it, a task only Loom creates) stops the converted build from loading. Gradle names the
        // statement it stopped at; it is commented out with a note, listed, and the build is loaded again.
        for (int attempt = 0; model == null; attempt++) {
            try {
                model = fresh.model(true, false);
            } catch (IOException failure) {
                Matcher log = Pattern.compile("\\nLog: (.+)$").matcher(failure.getMessage());
                String gradle = log.find() && Files.isRegularFile(Path.of(log.group(1).strip())) ? Files.readString(Path.of(log.group(1).strip())) : "";
                if (attempt >= 8 || !(nonTransitiveCompileOnly(gradle) || disableLoomOnlyStatement(gradle))) {
                    result.put("compiled", false);
                    result.put("ok", false);
                    result.put("compileError", failure.getMessage());
                    return;
                }
            }
        }
        List<String> tasks = new ArrayList<>(fresh.tasks(model, "classes"));
        Map<String, String> unwired = unwiredSourceSets(model);
        if (!unwired.isEmpty()) {
            tasks.removeAll(unwired.keySet());
            convert.notes.add(
                "Not compiled: source set(s) " + String.join(", ", unwired.values()) +
                ", whose classpath the build declares with neither the game nor the mod's own classes (the original's declaration, unchanged), so no loader compiles them."
            );
        }
        Run.Result run = fresh.gradle(tasks, "convert-compile");
        boolean ok = run.exit() == 0;
        // The original compiled on its own loader: an override the compiler now refuses is of a method that loader adds to
        // a game class (a hook), which nothing here calls. It is kept as a plain method and listed (unhooked).
        for (int attempt = 0; !ok && attempt < 4 && repairOverrides(Port.errors(run.log())) + repairKeyMappings(Port.errors(run.log())) > 0; attempt++) {
            run = fresh.gradle(tasks, "convert-compile");
            ok = run.exit() == 0;
        }
        if (!unhooked.isEmpty()) {
            result.put("unhooked", new ArrayList<>(unhooked));
            convert.notes.add("Overrides of the other loader's hooks on game classes, which nothing calls here, kept as plain methods: " + String.join(", ", unhooked) + ".");
        }
        result.put("compiled", ok);
        result.put("compileLog", run.log().toString());
        if (!ok) result.put("errors", Port.errors(run.log()));
        result.put("ok", ok && convert.projectFiles.left.isEmpty());
    }

    /**
     * Source sets besides main whose runtime classpath has neither a game jar nor main's output (a datagen set the
     * build never wires up): the loader's plugin gives the game to main (and test) only, so neither loader's build
     * compiles them. By classes task ("project:datagenClasses") to the set's name.
     */
    static Map<String, String> unwiredSourceSets(Map<String, Object> model) {
        Map<String, String> unwired = new LinkedHashMap<>();
        Pattern game = Pattern.compile("(?i)(minecraft|neoforge|forge)[^/\\\\]*\\.jar$");
        for (Object item : Json.array(model.get("projects"))) {
            var p = Json.object(item);
            String prefix = p.get("path").equals(":") ? "" : String.valueOf(p.get("path"));
            Set<String> mainOutput = new HashSet<>();
            boolean resolved = false;
            for (Object s : Json.array(p.get("sourceSets"))) if ("main".equals(Json.object(s).get("name"))) {
                mainOutput.addAll(ProjectModel.strings(Json.object(s).get("output")));
                resolved = !ProjectModel.strings(Json.object(s).get("runtime")).isEmpty();
            }
            // A model read without classpaths says nothing about wiring.
            if (!resolved) continue;
            for (Object s : Json.array(p.get("sourceSets"))) {
                var set = Json.object(s);
                String name = Json.text(set, "name", "");
                String task = Json.text(set, "classesTask", "");
                if (name.equals("main") || task.isEmpty()) continue;
                List<String> runtime = ProjectModel.strings(set.get("runtime"));
                boolean wired = runtime.stream().anyMatch(path -> mainOutput.contains(path) || game.matcher(path).find());
                if (!wired) unwired.put(prefix + ":" + task, prefix.isEmpty() ? name : prefix + ":" + name);
            }
        }
        return unwired;
    }

    /** The other loader's hooks the converted code overrides, which nothing calls here ("Class.method"). */
    final Set<String> unhooked = new TreeSet<>();

    /**
     * Repairs the overrides the compiler refuses after conversion, which overrode what the original loader adds to game
     * classes: "does not override" loses its @Override (the method stays, listed in unhooked), and an override narrower
     * than the game's method ("cannot override", with weaker access) becomes public, as the other loader's access
     * transformer had made the game's method narrower. Returns how many lines it changed.
     */
    int repairOverrides(List<Object> errors) throws IOException {
        int changed = 0;
        Map<Path, List<String>> files = new LinkedHashMap<>();
        for (Object item : errors) {
            var error = Json.object(item);
            String message = String.valueOf(error.get("message"));
            boolean notOverriding = message.startsWith("method does not override or implement a method from a supertype");
            // javac's first line ("m() in A cannot override m() in B"); its reason (weaker access, was public) follows.
            boolean narrower = message.matches(".*\\w+\\(.*\\) in \\S+ cannot override .*");
            if (!notOverriding && !narrower) continue;
            Path file = Path.of(String.valueOf(error.get("file")));
            if (!Files.isRegularFile(file) || !file.toAbsolutePath().normalize().startsWith(convert.project.root.toAbsolutePath().normalize())) continue;
            List<String> lines = files.computeIfAbsent(file, f -> {
                try {
                    return new ArrayList<>(Files.readAllLines(f, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            int index = ((Number) error.get("line")).intValue() - 1;
            if (index < 0 || index >= lines.size()) continue;
            String line = lines.get(index);
            String cls = file.getFileName().toString().replaceAll("\\.java$", "");
            if (notOverriding && line.contains("@Override")) {
                String rest = line.replaceFirst("@Override\\s*", "");
                lines.set(index, rest.isBlank() ? null : rest);
                for (int i = index; i < Math.min(lines.size(), index + 4); i++) {
                    Matcher method = Pattern.compile("(\\w+)\\s*\\(").matcher(lines.get(i) == null ? "" : lines.get(i));
                    if (method.find()) {
                        unhooked.add(cls + "." + method.group(1));
                        break;
                    }
                }
                changed++;
            } else if (narrower) {
                String widened = line.replaceFirst("^(\\s*)(?:protected\\s+|private\\s+)?", "$1public ");
                if (!widened.equals(line) && !line.trim().startsWith("public ")) {
                    lines.set(index, widened);
                    changed++;
                }
            }
        }
        for (var e : files.entrySet()) {
            List<String> kept = e.getValue().stream().filter(java.util.Objects::nonNull).toList();
            Files.writeString(e.getKey(), String.join("\n", kept) + "\n", StandardCharsets.UTF_8);
        }
        return changed;
    }

    /**
     * NeoForge's KeyMapping constructors that take a conflict context held in a variable (the rules match only a written
     * KeyConflictContext.X): javac refuses them on Fabric, whose KeyMapping has none ("X cannot be converted to Type", or no
     * suitable constructor), and the NeoForge bridge's static KeyConflictContext.keyMapping(name, context, ...) makes them.
     * Returns how many calls it changed.
     */
    int repairKeyMappings(List<Object> errors) throws IOException {
        if (!convert.neoForgeBridge.bridgeUsed || !Files.isRegularFile(convert.project.root.resolve(convert.neoForgeBridge.bridgeFolder() + "/KeyConflictContext.java"))) return 0;
        Map<Path, Set<Integer>> lines = new LinkedHashMap<>();
        for (Object item : errors) {
            var error = Json.object(item);
            String message = String.valueOf(error.get("message"));
            if (!message.matches("incompatible types: \\S+ cannot be converted to (?:Type|Key)") && !message.startsWith("no suitable constructor found for KeyMapping(")) continue;
            Path file = Path.of(String.valueOf(error.get("file")));
            if (!Files.isRegularFile(file) || !file.toAbsolutePath().normalize().startsWith(convert.project.root.toAbsolutePath().normalize())) continue;
            lines.computeIfAbsent(file, f -> new TreeSet<>()).add(((Number) error.get("line")).intValue());
        }
        int changed = 0;
        for (var e : lines.entrySet()) {
            String text = Files.readString(e.getKey(), StandardCharsets.UTF_8);
            String out = contextKeyMappings(text, e.getValue(), convert.neoForgeBridge.bridgePackage);
            if (out.equals(text)) continue;
            Files.writeString(e.getKey(), out, StandardCharsets.UTF_8);
            changed++;
            convert.applied.computeIfAbsent("neoforge-key-mapping-context-variable", k -> new ArrayList<>()).add(convert.project.root.relativize(e.getKey()).toString().replace('\\', '/'));
        }
        return changed;
    }

    /** The text with new KeyMapping( on the given lines (1-based) made the bridge's KeyConflictContext.keyMapping(, imported. */
    static String contextKeyMappings(String text, Set<Integer> lineNumbers, String bridgePackage) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        boolean changed = false;
        for (int number : lineNumbers) {
            if (number < 1 || number > lines.size() || !lines.get(number - 1).contains("new KeyMapping(")) continue;
            lines.set(number - 1, lines.get(number - 1).replaceFirst("new KeyMapping\\(", "KeyConflictContext.keyMapping("));
            changed = true;
        }
        if (!changed) return text;
        String out = String.join("\n", lines);
        String own = "import " + bridgePackage + ".KeyConflictContext;";
        boolean samePackage = Pattern.compile("(?m)^package\\s+" + Pattern.quote(bridgePackage) + "\\s*;").matcher(out).find();
        if (samePackage || out.contains(own)) return out;
        Matcher anchor = Pattern.compile("(?m)^(?:import\\s[^;]+;|package\\s[^;]+;)[ \\t]*$").matcher(out);
        int at = -1;
        while (anchor.find()) at = anchor.end();
        return at < 0 ? own + "\n" + out : out.substring(0, at) + "\n" + own + out.substring(at);
    }

    static final String LOOM_ONLY ="// mmt convert: needs Fabric's Loom, which a NeoForge build does not have: ";

    /**
     * A compile-only dependency whose own dependencies Gradle cannot find (the other loader's build of an integration mod,
     * which needs libraries from a repository the build does not list): compiling against it needs only its own classes,
     * so it is made non-transitive and listed. False when the failure names no compile-only dependency of build.gradle.
     */
    boolean nonTransitiveCompileOnly(String gradle) throws Exception {
        if (!convert.projectFiles.exists("build.gradle")) return false;
        String text = convert.projectFiles.read("build.gradle");
        String out = nonTransitiveCompileOnly(gradle, text, BuildScript.properties(convert.projectFiles.exists("gradle.properties") ? convert.projectFiles.read("gradle.properties") : ""));
        if (out == null) return false;
        convert.projectFiles.write("build.gradle", out);
        convert.repairedBuild = true;
        convert.applied.computeIfAbsent("compile-only-non-transitive", k -> new ArrayList<>()).add("build.gradle");
        convert.notes.add("Build: compile-only dependencies whose own dependencies Gradle could not find are compiled against without them (transitive = false).");
        return true;
    }

    /** The build with each compile-only dependency a "Could not find ... Required by: root project ... > G:A:V" names made non-transitive, or null. */
    static String nonTransitiveCompileOnly(String gradle, String build, Map<String, String> properties) {
        Set<String> requirers = new LinkedHashSet<>();
        Matcher by = Pattern.compile("root project '[^']*' > ([\\w.\\-]+):([\\w.\\-]+):").matcher(gradle);
        while (by.find()) requirers.add(by.group(1) + ":" + by.group(2) + ":");
        if (requirers.isEmpty()) return null;
        Pattern call = Pattern.compile("^(\\s*)(\\w*[cC]ompileOnly\\w*)\\s*(?:\\(\\s*(([\"'])[^\"']+\\4)\\s*\\)|(([\"'])[^\"']+\\6))\\s*$");
        String[] lines = build.split("\n", -1);
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            Matcher m = call.matcher(lines[i].replaceAll("\\r$", ""));
            if (!m.matches()) continue;
            String quoted = m.group(3) != null ? m.group(3) : m.group(5);
            String coordinate = quoted.substring(1, quoted.length() - 1);
            Matcher variable = Pattern.compile("\\$\\{(\\w+)}|\\$(\\w+)").matcher(coordinate);
            StringBuilder expanded = new StringBuilder();
            while (variable.find()) {
                String key = variable.group(1) != null ? variable.group(1) : variable.group(2);
                variable.appendReplacement(expanded, Matcher.quoteReplacement(properties.getOrDefault(key, variable.group())));
            }
            variable.appendTail(expanded);
            String resolved = expanded.toString();
            if (requirers.stream().noneMatch(resolved::startsWith)) continue;
            lines[i] = m.group(1) + m.group(2) + "(" + quoted + ") { transitive = false }";
            changed = true;
        }
        return changed ? String.join("\n", lines) : null;
    }

    /**
     * The statement of the build's own files at which Gradle stopped loading the converted build (its "* Where:" file and
     * line), commented out with a note and listed as left: a plugin line inside plugins { } alone, any other statement
     * with the block it opens. A failure inside a remote or shared script disables the project's apply from that names
     * it. False when the failure names no statement of the project's files, or one already commented out.
     */
    boolean disableLoomOnlyStatement(String gradle) throws Exception {
        Matcher where = Pattern.compile("\\* Where:\\s*\\n(?:Build file|Script|Settings file|Initialization script) '([^']+)' line: (\\d+)").matcher(gradle);
        if (!where.find()) return false;
        Matcher wrong = Pattern.compile("\\* What went wrong:\\s*\\r?\\n([^\\r\\n]+)(?:\\r?\\n> ([^\\r\\n]+))?").matcher(gradle);
        String why = wrong.find() ? (wrong.group(2) != null ? wrong.group(2) : wrong.group(1)).strip() : "the build stopped loading here";
        String where0 = where.group(1).replace('\\', '/');
        int line = Integer.parseInt(where.group(2));
        String file = null;
        Path root = convert.project.root.toAbsolutePath().normalize();
        if (!where0.contains("://")) {
            Path p = Path.of(where.group(1)).toAbsolutePath().normalize();
            if (p.startsWith(root)) file = root.relativize(p).toString().replace('\\', '/');
        }
        if (file == null || file.startsWith(".mmt/") || file.startsWith(".gradle/")) {
            // A shared script outside the project: the project's own apply from that names it.
            // By the script's name, else (a script applied by another shared script) by the folder it was published in.
            String name = where0.substring(where0.lastIndexOf('/') + 1);
            String folder = where0.substring(0, where0.lastIndexOf('/') + 1);
            for (String key : List.of(name, folder)) for (String candidate : List.of("build.gradle", "settings.gradle")) {
                String[] lines = convert.projectFiles.read(candidate).split("\n", -1);
                for (int i = 0; i < lines.length; i++) if (lines[i].contains("apply") && lines[i].contains(key) && !lines[i].strip().startsWith("//")) {
                    return disableLines(candidate, i, i, why);
                }
            }
            return false;
        }
        String[] lines = convert.projectFiles.read(file).split("\n", -1);
        int start = line - 1;
        if (start < 0 || start >= lines.length || lines[start].strip().startsWith("//")) return false;
        int end = start;
        if (!insidePlugins(lines, start)) {
            int depth = 0;
            for (end = start; end < lines.length; end++) {
                String code = lines[end].replaceAll("//.*$", "").replaceAll("'[^']*'|\"[^\"]*\"", "");
                for (char c : code.toCharArray()) depth += c == '{' || c == '(' ? 1 : c == '}' || c == ')' ? -1 : 0;
                if (depth <= 0) break;
            }
            end = Math.min(end, lines.length - 1);
        }
        return disableLines(file, start, end, why);
    }

    boolean disableLines(String file, int start, int end, String why) throws Exception {
        String[] lines = convert.projectFiles.read(file).split("\n", -1);
        String indent = lines[start].replaceAll("^(\\s*).*$", "$1");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i == start) out.append(indent).append(LOOM_ONLY).append(why.replaceAll("\\s+", " ")).append('\n');
            out.append(i >= start && i <= end ? lines[i].replaceAll("^(\\s*)", "$1// ") : lines[i]);
            if (i < lines.length - 1) out.append('\n');
        }
        convert.projectFiles.write(file, out.toString());
        convert.repairedBuild = true;
        convert.projectFiles.leave(file, start + 1, lines[start].strip(), "needs Fabric's Loom, which a NeoForge build does not have (" + why + "); commented out");
        return true;
    }

    static boolean insidePlugins(String[] lines, int index) {
        int depth = 0;
        for (int i = index - 1; i >= 0; i--) {
            String code = lines[i].replaceAll("//.*$", "");
            for (int c = code.length() - 1; c >= 0; c--) depth += code.charAt(c) == '}' ? 1 : code.charAt(c) == '{' ? -1 : 0;
            if (depth < 0) return code.matches("\\s*plugins\\s*\\{.*");
        }
        return false;
    }
}
