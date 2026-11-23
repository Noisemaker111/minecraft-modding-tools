import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.*;

/** The port rules (knowledge/fixes.json) applied to the mod's source: which rule fits which compiler error, and the edit it makes. */
final class PortRules {

    final Port port;

    PortRules(Port port) {
        this.port = port;
    }

    /** How many of the rules a port applied came from each source (knowledge/fixes.json "provenance"). */
    /** Whether port --learn-from-mods was given: only then do rules mined from other people's mods run. */
    boolean learnFromMods;

    /**
     * The rule book's own rules always run; the older rules mined from third-party mods (provenance third-party-mods)
     * only with --learn-from-mods. Rules are never taken from other people's mods by default.
     */
    static boolean ownRule(Map<String, Object> rule, boolean learnFromMods) {
        return learnFromMods || !"third-party-mods".equals(Json.text(rule, "provenance", ""));
    }

    Map<String, Object> provenance(Set<String> ids) throws IOException {
        Map<String, Object> counts = new TreeMap<>();
        if (ids.isEmpty()) return counts;
        for (Object item : Json.array(Json.read(port.build.harness.resolve("knowledge/fixes.json")).get("rules"))) {
            var rule = Json.object(item);
            if (!ids.contains(Json.text(rule, "id", ""))) continue;
            counts.merge(Json.text(rule, "provenance", "unrecorded"), 1, (a, b) -> (Integer) a + (Integer) b);
        }
        return counts;
    }

    /**
     * A rule with "loader" runs only on a project built for that loader: NeoForge's GuiLayer replaces 1.21.6's removed
     * LayeredDraw.Layer, while a Fabric mod needs Fabric's HUD API for the same error.
     */
    boolean loaderFits(Map<String, Object> rule) {
        Set<String> loaders = new TreeSet<>();
        for (Object p : Json.array(port.model.get("projects"))) loaders.addAll(Support.loaders(Json.object(p)));
        return loaderFits(rule, loaders);
    }

    static final Pattern GAME_CLASS = Pattern.compile(
        "\\b(?:net\\.minecraft|com\\.mojang|net\\.neoforged|net\\.fabricmc)(?:\\.[a-z]\\w*)+\\.[A-Z]\\w*"
    );

    /**
     * Whether every game class a rule writes (its ensureImports, and a qualified name in its replacement) is in the target
     * game. A rule from an earlier update can write a class a later one removed again (1.21.2's CoreShaders, gone in
     * 1.21.5): on a longer jump it would only trade one error for another, so it is skipped and the error stays named
     * by the later update. True when the target's classpath cannot be read.
     */
    boolean targetHas(Map<String, Object> rule) throws IOException {
        var apply = Json.object(rule.get("apply"));
        Set<String> written = new TreeSet<>(ProjectModel.strings(apply.get("ensureImports")));
        Matcher named = GAME_CLASS.matcher(Json.text(apply, "replace", ""));
        while (named.find()) written.add(named.group());
        written.removeIf(name -> !GAME_CLASS.matcher(name).matches());
        if (written.isEmpty()) return true;
        if (port.hierarchy.supertypeIndex == null) port.hierarchy.supertypeIndex = new Explain.Index(Port.classpath(port.model));
        if (port.hierarchy.supertypeIndex.bySimple.getOrDefault("Minecraft", List.of()).isEmpty()) return true;
        for (String name : written) if (port.hierarchy.supertypeIndex.qualified(name) == null && !port.maps.mapRenames(name)) return false;
        return true;
    }

    static boolean loaderFits(Map<String, Object> rule, Set<String> loaders) {
        String loader = Json.text(rule, "loader", "");
        return loader.isEmpty() || loaders.contains(loader);
    }

    /** The selected project's own Java sources: a repo can hold other versions' copies (a Forge 1.20.1 sibling, generated Stonecutter sources) that this port must not touch. */
    List<Path> ownedJava() throws IOException {
        Set<Path> owned = new HashSet<>();
        for (Object item : Json.array(port.model.get("projects"))) for (Object set : Json.array(
            Json.object(item).get("sourceSets")
        )) {
            for (String dir : ProjectModel.strings(Json.object(set).get("java"))) owned.add(
                Path.of(dir).toAbsolutePath().normalize()
            );
        }
        return Port.sources(port.build.project)
            .stream()
            .filter(p -> p.toString().endsWith(".java"))
            .filter(p -> owned.isEmpty() || owned.stream().anyMatch(p.toAbsolutePath().normalize()::startsWith))
            .toList();
    }

    /**
     * Rules for changes the compiler never reports (a rule with "silent" saying what goes wrong instead): they run once,
     * before the first build, over every owned source, when the port crosses their update. In 1.21.6 a GUI colour
     * without an alpha byte still compiles and draws nothing.
     */
    void applySilentRules(String from, String to) throws Exception {
        // The project's own sources and those of the modules beside it (a multi-loader mod's common code is compiled
        // into each loader's project, and the compiler never reports what these rules fix).
        List<Path> all = new ArrayList<>(ownedJava());
        for (Path p : buildSources()) if (!all.contains(p)) all.add(p);
        String modId = null;
        for (Object item : Json.array(Json.read(port.build.harness.resolve("knowledge/fixes.json")).get("rules"))) {
            var rule = Json.object(item);
            if (!rule.containsKey("silent") || !loaderFits(rule) || !ownRule(rule, learnFromMods)) continue;
            List<String> covered = port.route == null
                ? List.of()
                : port.route.stepsCovered(Json.text(rule, "jump", ""), port.namespace);
            if (port.route != null ? covered.isEmpty() : !crosses(Json.text(rule, "jump", ""), from, to)) continue;
            if (!targetHas(rule)) continue;
            boolean ids = "registry-ids".equals(Json.object(rule.get("apply")).get("transform"));
            if (ids && modId == null) modId = singleModId();
            for (Path file : all) {
                String original = Files.readString(file), text = ids ? RegistryIdMigration.registryIds(original, modId) : rewrite(rule, original);
                if (!text.equals(original)) {
                    Files.writeString(file, text);
                    port.rulesApplied.putIfAbsent(Json.text(rule, "id", "rule"), covered);
                }
            }
        }
    }

    /**
     * A moved or renamed game class written inside a string (a mixin target "of(Lnet/minecraft/client/item/TooltipData;)..."
     * or @Mixin(targets = "net.minecraft...")) gets its full new name from the port's class map, before anything renames
     * it by its simple name alone and leaves the old package behind.
     */
    void renameClassesInStrings(Path map) throws Exception {
        if (!Files.isRegularFile(map)) return;
        Object listed = Json.read(map).get("classes");
        if (!(listed instanceof List<?> list) || list.isEmpty()) return;
        Map<String, String> classes = new HashMap<>();
        for (Object item : list) {
            var entry = Json.object(item);
            classes.put(Json.text(entry, "from", ""), Json.text(entry, "to", ""));
        }
        for (Path file : ownedJava()) {
            String original = Files.readString(file), text = classNamesInStrings(original, classes);
            if (!text.equals(original)) {
                Files.writeString(file, text);
                port.rulesApplied.putIfAbsent("class-names-in-strings", List.of());
            }
        }
    }

    /** The directory of the whole build: the nearest one up from the project with a Gradle settings file. */
    Path buildRoot() {
        Path start = port.build.project.root.toAbsolutePath().normalize();
        for (Path p = start; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("settings.gradle")) || Files.isRegularFile(p.resolve("settings.gradle.kts"))) return p;
        }
        return start;
    }

    /** Files under the build root whose path contains PART, skipping build output, run folders and dot folders. */
    List<Path> buildFiles(String part) throws IOException {
        Path root = buildRoot();
        List<Path> out = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
                return !dir.equals(root) && (n.startsWith(".") || List.of("build", "run", "runs", "out", "bin").contains(n))
                    ? FileVisitResult.SKIP_SUBTREE
                    : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (file.toString().replace('\\', '/').contains(part)) out.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException unreadable) {
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    List<Path> buildSources() throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path p : buildFiles("/src/main/java/")) if (p.toString().endsWith(".java")) out.add(p);
        return out;
    }

    /** The mod's id, when the sources' metadata names exactly one (neoforge.mods.toml, mods.toml or fabric.mod.json). */
    String singleModId() throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        // A multi-loader mod keeps its id in the build root's gradle.properties.
        Path root = buildRoot();
        java.util.Properties gradle = Files.isRegularFile(root.resolve("gradle.properties"))
            ? ProjectModel.properties(root.resolve("gradle.properties"))
            : new java.util.Properties();
        for (Path p : buildFiles("/src/main/resources/")) {
            String name = p.getFileName().toString();
            Matcher id = null;
            if (name.equals("neoforge.mods.toml") || name.equals("mods.toml")) {
                id = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"").matcher(Files.readString(p));
            } else if (name.equals("fabric.mod.json")) {
                id = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(Files.readString(p));
            }
            if (id == null || !id.find()) continue;
            String value = id.group(1);
            Matcher template = Pattern.compile("^\\$\\{(\\w+)}$").matcher(value);
            if (template.find()) value = gradle.getProperty(template.group(1));
            if (value != null && value.strip().matches("[a-z0-9_.-]+")) ids.add(value.strip());
        }
        return ids.size() == 1 ? ids.iterator().next() : null;
    }

    static String classNamesInStrings(String text, Map<String, String> classes) {
        // Unrolled and possessive: an alternation under * recursed once per character and overflowed the stack on a
        // long string literal (DragonMine Z).
        Matcher literal = Pattern.compile("\"[^\"\\\\\\n]*+(?:\\\\.[^\"\\\\\\n]*+)*+\"").matcher(text);
        StringBuilder out = new StringBuilder();
        while (literal.find()) {
            // A name starts the string, follows punctuation, or follows a descriptor's L ("(Lnet/minecraft/...;").
            Matcher name = Pattern.compile("(?:(?<=[^\\w$]L)|(?<![\\w$./]))net([/.])minecraft\\1[\\w/.$]*\\w").matcher(
                literal.group()
            );
            StringBuilder renamed = new StringBuilder();
            while (name.find()) {
                String separator = name.group(1), dotted = name.group().replace('/', '.'), to = classes.get(dotted);
                // A dotted name may run on into a member ("net.minecraft.Foo.bar"): try its longest known class prefix.
                String rest = "";
                while (to == null && dotted.contains(".")) {
                    rest = dotted.substring(dotted.lastIndexOf('.')) + rest;
                    dotted = dotted.substring(0, dotted.lastIndexOf('.'));
                    to = classes.get(dotted);
                }
                name.appendReplacement(
                    renamed,
                    Matcher.quoteReplacement(
                        to == null
                            ? name.group()
                            : (separator.equals("/") ? to.replace('.', '/') : to) +
                            (separator.equals("/") ? rest.replace('.', '/') : rest)
                    )
                );
            }
            name.appendTail(renamed);
            literal.appendReplacement(out, Matcher.quoteReplacement(renamed.toString()));
        }
        literal.appendTail(out);
        return out.toString();
    }

    /** True when a port from -> to crosses the release a jump ("A -> B" changed in B, "X+" in X) names, either way. */
    static boolean crosses(String jump, String from, String to) {
        Matcher release = Pattern.compile("(?:->\\s*)?(\\d+(?:\\.\\d+)+)(\\+)?(?!.*->)").matcher(jump);
        if (!release.find()) return false;
        String changed = release.group(1);
        return Versions.compare(from, changed) < 0
            ? Versions.compare(to, changed) >= 0
            : Versions.compare(to, changed) < 0;
    }

    void applyRules(Path log) throws Exception {
        port.sourceMixins.removeDeadMixinTargets(log);
        port.access.repairAccess(log);
        port.sourceMixins.mixinConstructors(log);
        String errors = Port.logText(log);
        List<Path> all = ownedJava();
        Set<Path> logged = new HashSet<>();
        Matcher reported = Pattern.compile("(?m)^\\s*" + Port.FILE + ":[0-9]+: error:").matcher(errors);
        while (reported.find()) {
            Path file = Path.of(reported.group(1));
            if (!file.isAbsolute()) file = port.build.project.root.resolve(file);
            file = file.normalize();
            if (file.startsWith(port.build.project.root)) logged.add(file);
        }
        // A file javac reported while building this target is part of it, even when another project owns it (an
        // Architectury :common compiled into :fabric).
        Set<Path> withLogged = new LinkedHashSet<>(all);
        for (Path file : logged) if (Files.isRegularFile(file) && file.toString().endsWith(".java")) withLogged.add(file);
        all = new ArrayList<>(withLogged);
        for (Object item : Json.array(Json.read(port.build.harness.resolve("knowledge/fixes.json")).get("rules"))) {
            var rule = Json.object(item);
            if (
                rule.containsKey("silent") ||
                !ownRule(rule, learnFromMods) ||
                !loaderFits(rule) ||
                !Pattern.compile(rule.get("match").toString()).matcher(errors).find()
            ) continue;
            // A routed port runs a rule only when its jump covers an update the route crosses and its names fit.
            List<String> covered = port.route == null
                ? List.of()
                : port.route.stepsCovered(Json.text(rule, "jump", ""), port.namespace);
            if (port.route != null && covered.isEmpty()) continue;
            if (!targetHas(rule)) continue;
            Object transform = Json.object(rule.get("apply")).get("transform");
            if ("accessor".equals(transform) || "payload-bytes".equals(transform) || "neo-config".equals(transform) || "neo-entry".equals(transform)) {
                boolean applied = "neo-entry".equals(transform) ? port.neoEntryMigration.applyNeoEntry(rule, errors)
                    : "neo-config".equals(transform) ? port.neoConfigMigration.applyNeoConfig(rule, errors)
                    : "payload-bytes".equals(transform) ? port.payloadMigration.applyPayloadBytes(rule, errors)
                    : port.sourceMixins.applyAccessor(rule, errors);
                if (applied) port.rulesApplied.putIfAbsent(Json.text(rule, "id", "rule"), covered);
                continue;
            }
            if ("food-consumable".equals(transform)) {
                // The foods are built in one class, and every item made from one, anywhere in the mod, eats as it said.
                Pattern when = Pattern.compile(Json.text(rule, "when", "."));
                for (Path file : logged) {
                    if (!all.contains(file)) continue;
                    String original = Files.readString(file);
                    if (!when.matcher(original).find()) continue;
                    String built = FoodMigration.foodConsumables(original);
                    if (built.equals(original)) continue;
                    Files.writeString(file, built);
                    String owner = FoodMigration.foodOwner(built);
                    for (Path user : all) {
                        String text = Files.readString(user), used = FoodMigration.foodUses(text, owner);
                        if (!used.equals(text)) Files.writeString(user, used);
                    }
                    port.rulesApplied.putIfAbsent(Json.text(rule, "id", "rule"), covered);
                }
                continue;
            }
            if ("line".equals(rule.get("scope"))) {
                for (Path file : logged) {
                    if (!all.contains(file)) continue;
                    String original = Files.readString(file), text = rewriteReported(rule, original, errors, named -> {
                        Path path = Path.of(named);
                        return (path.isAbsolute() ? path : port.build.project.root.resolve(path)).normalize().equals(file);
                    });
                    if (!text.equals(original)) {
                        Files.writeString(file, deferImports(file, original, text));
                        port.rulesApplied.putIfAbsent(Json.text(rule, "id", "rule"), covered);
                    }
                }
                continue;
            }
            for (Path file : all) {
                if (!"repo".equals(rule.get("scope")) && !logged.contains(file)) continue;
                String original = Files.readString(file), text = rewrite(rule, original);
                if (!text.equals(original)) {
                    Files.writeString(file, deferImports(file, original, text));
                    port.rulesApplied.putIfAbsent(Json.text(rule, "id", "rule"), covered);
                }
            }
        }
    }

    /**
     * Imports a repair round adds wait until the round's line-based steps are done (these rules and the typed fixer read
     * javac's line numbers from the same log): an import line inserted first would aim every later step below it one
     * line off. Takes the import lines addImport put after the package line back out of text and keeps them for
     * flushImports.
     */
    final Map<Path, Set<String>> pendingImports = new LinkedHashMap<>();

    String deferImports(Path file, String original, String text) {
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        int added = lines.size() - original.split("\n", -1).length;
        if (added <= 0) return text;
        Set<String> had = new HashSet<>();
        for (String line : original.split("\n", -1)) had.add(line.strip());
        int at = 0;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).matches("\\s*package\\s+[\\w.]+\\s*;.*")) {
            at = i + 1;
            break;
        }
        while (
            added > 0 &&
            at < lines.size() &&
            lines.get(at).strip().matches("import [\\w.]+;") &&
            !had.contains(lines.get(at).strip())
        ) {
            String line = lines.remove(at).strip();
            added--;
            pendingImports.computeIfAbsent(file, f -> new LinkedHashSet<>()).add(line.substring(7, line.length() - 1));
        }
        return String.join("\n", lines);
    }

    void flushImports() throws IOException {
        for (var entry : pendingImports.entrySet()) {
            if (!Files.isRegularFile(entry.getKey())) continue;
            String text = Files.readString(entry.getKey()), out = text;
            for (String name : entry.getValue()) out = JavaText.addImport(out, name);
            if (!out.equals(text)) Files.writeString(entry.getKey(), out);
        }
        pendingImports.clear();
    }

    /**
     * The repair guides (knowledge/structural.json) for what a stopped port left: a guide is offered when its match finds
     * one of javac's errors and the port crosses the release its jump names ("A -> B" changed in B, "X+" in X), either way.
     */
    static List<Map<String, Object>> repairGuides(Path harness, String from, String to, String log) throws IOException {
        Path file = harness.resolve("knowledge/structural.json");
        if (!Files.exists(file)) return List.of();
        StringBuilder errors = new StringBuilder();
        // javac's own blocks (an error line and the lines under it), not Gradle's repeats or the rest of the log.
        List<String> lines = log.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (!Port.ERROR.matcher(lines.get(i)).find()) continue;
            errors.append(lines.get(i)).append('\n');
            for (int j = i + 1; j < lines.size() && j <= i + 5 && !Port.ERROR.matcher(lines.get(j)).find(); j++) errors
                .append(lines.get(j))
                .append('\n');
        }
        List<Map<String, Object>> guides = new ArrayList<>();
        for (Object item : Json.array(Json.parse(Files.readString(file, StandardCharsets.UTF_8)))) {
            var guide = Json.object(item);
            if (
                !crosses(Json.text(guide, "jump", ""), from, to) ||
                !Pattern.compile(Json.text(guide, "match", "(?!)")).matcher(errors).find()
            ) continue;
            guides.add(
                Json.map(
                    "id",
                    guide.get("id"),
                    "title",
                    guide.get("title"),
                    "steps",
                    guide.get("steps"),
                    "note",
                    guide.get("note")
                )
            );
        }
        return guides;
    }

    /** Each project file javac reported an error in whose block the pattern fits, with those lines and the symbol each error names. */
    Map<Path, Map<Integer, String>> reportedLines(String errors, Pattern match) {
        Map<Path, Map<Integer, String>> sites = new LinkedHashMap<>();
        List<String> log = errors.lines().toList();
        for (int i = 0; i < log.size(); i++) {
            Matcher error = Port.ERROR.matcher(log.get(i));
            if (!error.find()) continue;
            StringBuilder block = new StringBuilder(log.get(i)).append('\n');
            for (int j = i + 1; j < log.size() && j <= i + 5 && !Port.ERROR.matcher(log.get(j)).find(); j++) block
                .append(log.get(j))
                .append('\n');
            if (!match.matcher(block).find()) continue;
            Path file = Path.of(error.group(1));
            if (!file.isAbsolute()) file = port.build.project.root.resolve(file);
            file = file.normalize();
            if (!file.startsWith(port.build.project.root) || !Files.isRegularFile(file)) continue;
            Matcher symbol = Pattern.compile("symbol:\\s+\\w+ (\\w+)").matcher(block);
            sites
                .computeIfAbsent(file, f -> new TreeMap<>())
                .put(Integer.parseInt(error.group(2)), symbol.find() ? symbol.group(1) : "");
        }
        return sites;
    }

    /** One knowledge rule on one source text: its `when` gate, the literal or regex rewrite, then any imports it needs. */
    /**
     * A rule's replacement for one match: $N is group N as usual, and $^N is group N in UPPER_SNAKE case, for a getter the
     * game turned into a constant (1.21.2's GameRenderer::getPositionTexShader -> CoreShaders.POSITION_TEX). The result
     * is still a Matcher replacement: replaceAll expands the plain $N itself.
     */
    static String expandReplacement(String replace, java.util.regex.MatchResult match) {
        Matcher token = Pattern.compile("\\$\\^(\\d)").matcher(replace);
        StringBuilder out = new StringBuilder();
        while (token.find()) {
            String group = match.group(Integer.parseInt(token.group(1)));
            String upper = group == null
                ? ""
                : group.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replaceAll("([A-Z])([A-Z][a-z])", "$1_$2").toUpperCase(Locale.ROOT);
            // Quoted twice: once for this appendReplacement, once for the replaceAll that reads the result.
            token.appendReplacement(out, Matcher.quoteReplacement(Matcher.quoteReplacement(upper)));
        }
        return token.appendTail(out).toString();
    }

    static String rewrite(Map<String, Object> rule, String original) {
        return keepingLineEndings(original, text -> rewriteLf(rule, text));
    }

    interface SourceTransform<E extends Exception> {
        String apply(String text) throws E;
    }

    /**
     * Rules and source transforms are written against LF. A Windows checkout (core.autocrlf) has CRLF, where a find
     * spanning lines with \n never matched and a line's trailing \r defeated .*: the 1.7.10 key, tick, GUI, config and
     * mixin conversions skipped every Windows port. The transform runs on LF and the file keeps CRLF.
     */
    static <E extends Exception> String keepingLineEndings(String original, SourceTransform<E> transform) throws E {
        if (!original.contains("\r\n")) return transform.apply(original);
        String lf = original.replace("\r\n", "\n");
        String text = transform.apply(lf);
        return text.equals(lf) ? original : text.replace("\r\n", "\n").replace("\n", "\r\n");
    }

    static String rewriteLf(Map<String, Object> rule, String original) {
        var apply = Json.object(rule.get("apply"));
        String text = original;
        String when = Json.text(rule, "when", "");
        if (!when.isEmpty() && !Pattern.compile(when).matcher(text).find()) return original;
        if ("opaque-gui-colors".equals(apply.get("transform"))) text = GuiColorMigration.opaqueGuiColors(text);
        // A rule's test for a loader-swap transform converts one file: the entry-point wiring has no single-file form.
        else if ("neo-config".equals(apply.get("transform"))) text = NeoConfigMigration.neoConfigConvert(text);
        else if ("neo-entry".equals(apply.get("transform"))) text = NeoEntryMigration.neoSubscriberConvert(text);
        else if ("call-arguments".equals(apply.get("transform"))) text = CallArgumentMigration.callArguments(text, apply);
        else if ("entity-render-state".equals(apply.get("transform"))) text = EntityRenderStateMigration.entityRenderState(text);
        else if ("entity-model-render-state".equals(apply.get("transform"))) text = EntityRenderStateMigration.entityModelRenderState(text);
        else if ("toast-visibility".equals(apply.get("transform"))) text = ToastMigration.toastVisibility(text);
        else if ("food-effects".equals(apply.get("transform"))) text = FoodMigration.foodEffects(text);
        else if ("food-consumable".equals(apply.get("transform"))) text = FoodMigration.foodConsumables(text);
        else if ("registry-ids".equals(apply.get("transform"))) text = RegistryIdMigration.registryIds(text, "examplemod");
        // A rule's test has no project to put the accessor in: it reads as if the mod's mixin package were mod.mixin.
        else if ("accessor".equals(apply.get("transform"))) text = PortMixins.accessorCalls(
            text,
            apply,
            "mod.mixin." + JavaText.simpleName(Json.text(apply, "owner", "")) + "Accessor",
            null
        );
        else if ("payload-bytes".equals(apply.get("transform"))) text = PayloadMigration.payloadCalls(
            text,
            Json.text(apply, "name", "MmtPacket"),
            null
        );
        else if (apply.containsKey("literal")) text = text.replace(
            apply.get("literal").toString(),
            Json.text(apply, "replace", "")
        );
        else if (apply.containsKey("find")) text = Pattern.compile(apply.get("find").toString())
            .matcher(text)
            .replaceAll(match -> expandReplacement(Json.text(apply, "replace", ""), match));
        if (!text.equals(original)) for (String name : ProjectModel.strings(apply.get("ensureImports"))) text =
            JavaText.addImport(text, name);
        return text;
    }

    /**
     * A rule with scope "line" rewrites only the lines javac reported with an error its match fits, and {symbol} in its
     * find or replace stands for the symbol that error names. Used where the same call is right elsewhere in the file:
     * getCategory() needs .value() only where its receiver became a RegistryEntry.
     */
    /** A pattern that matches nothing: a {receiver} javac named no variable for leaves the line alone. */
    static final String NOTHING = "(?!)";

    static String rewriteReported(
        Map<String, Object> rule,
        String text,
        String errors,
        java.util.function.Predicate<String> sameFile
    ) {
        String when = Json.text(rule, "when", "");
        if (!when.isEmpty() && !Pattern.compile(when).matcher(text).find()) return text;
        Pattern match = Pattern.compile(rule.get("match").toString());
        List<String> log = errors.lines().toList();
        Map<Integer, String> sites = new TreeMap<>(), looked = new HashMap<>();
        for (int i = 0; i < log.size(); i++) {
            Matcher error = Port.ERROR.matcher(log.get(i));
            if (!error.find() || !sameFile.test(error.group(1))) continue;
            StringBuilder block = new StringBuilder(log.get(i)).append('\n');
            for (int j = i + 1; j < log.size() && j <= i + 5 && !Port.ERROR.matcher(log.get(j)).find(); j++) block
                .append(log.get(j))
                .append('\n');
            if (!match.matcher(block).find()) continue;
            Matcher symbol = Pattern.compile("symbol:\\s+\\w+ (\\w+)").matcher(block);
            sites.put(Integer.parseInt(error.group(2)), symbol.find() ? symbol.group(1) : "");
            // {receiver}: the variable javac looked the symbol up on ("location: variable stack of type ItemStack").
            Matcher receiver = Pattern.compile("location:\\s+variable (\\w+) of type").matcher(block);
            looked.put(Integer.parseInt(error.group(2)), receiver.find() ? receiver.group(1) : NOTHING);
        }
        if (sites.isEmpty()) return text;
        String[] lines = text.split("\n", -1);
        for (var site : sites.entrySet()) {
            int at = site.getKey() - 1;
            if (at < 0 || at >= lines.length) continue;
            var helper = Json.object(rule.get("apply"));
            // A helper rule's call: its own find and replace, or receiver.symbol(args) as name(receiver, args).
            if ("helper".equals(helper.get("transform"))) {
                lines[at] = helper.containsKey("find")
                    ? Pattern.compile(helper.get("find").toString())
                        .matcher(lines[at])
                        .replaceAll(Json.text(helper, "replace", ""))
                    : helperCalls(lines[at], helper, site.getValue());
                continue;
            }
            Map<String, Object> bound = new LinkedHashMap<>(rule), apply = new LinkedHashMap<>(
                Json.object(rule.get("apply"))
            );
            for (String key : List.of("find", "replace", "literal")) if (
                apply.get(key) instanceof String value
            ) apply.put(key, value.replace("{symbol}", site.getValue()).replace("{receiver}", looked.getOrDefault(site.getKey(), NOTHING)));
            apply.remove("ensureImports");
            bound.put("apply", apply);
            bound.put("when", "");
            lines[at] = rewrite(bound, lines[at]);
        }
        String out = String.join("\n", lines);
        if (!out.equals(text) && "helper".equals(Json.object(rule.get("apply")).get("transform"))) out = insertHelper(
            out,
            Json.object(rule.get("apply"))
        );
        if (!out.equals(text)) for (String name : ProjectModel.strings(
            Json.object(rule.get("apply")).get("ensureImports")
        )) out = JavaText.addImport(out, name);
        return out;
    }

    /** receiver.symbol(args) on one line becomes name(receiver, args): the call goes through a helper the port adds to the class. */
    static String helperCalls(String line, Map<String, Object> apply, String symbol) {
        String name = Json.text(apply, "name", "");
        Matcher call = Pattern.compile("\\.\\s*" + Pattern.quote(symbol) + "\\s*\\(").matcher(line);
        StringBuilder out = new StringBuilder();
        int copied = 0;
        while (call.find()) {
            int receiver = JavaText.receiverStart(line, call.start());
            if (receiver >= call.start() || receiver < copied) continue;
            boolean noArguments = line.substring(call.end()).stripLeading().startsWith(")");
            out
                .append(line, copied, receiver)
                .append(name)
                .append('(')
                .append(line, receiver, call.start())
                .append(noArguments ? "" : ", ");
            copied = call.end();
        }
        return copied == 0 ? line : out.append(line.substring(copied)).toString();
    }

    /** Adds a helper rule's method to the end of the file's top-level class, once. */
    static String insertHelper(String text, Map<String, Object> apply) {
        String name = Json.text(apply, "name", "");
        if (Pattern.compile("static[^;{=]*\\b" + Pattern.quote(name) + "\\s*\\(").matcher(text).find()) return text;
        int end = text.lastIndexOf('}');
        if (end < 0) return text;
        String body = text.substring(0, end).replaceAll("[ \\t]*$", "");
        return (
            body +
            (body.endsWith("\n") ? "" : "\n") +
            "\n" +
            Json.text(apply, "source", "") +
            "\n" +
            text.substring(end)
        );
    }
}
