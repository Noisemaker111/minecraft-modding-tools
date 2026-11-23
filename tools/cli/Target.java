import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;

/** Resolve published Fabric pins, explicit dependency selections and one safe manifest retarget. */
final class Target {

    record Edit(Path file, String before, String after) {}

    /** A lookup that got no answer (network error, 5xx, 429): never evidence that something is unpublished. */
    static final class LookupFailure extends IOException {

        LookupFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Accumulates every target change as text, so one file is never edited twice against stale content. */
    static final class Setup {

        final Build build;
        final Map<String, Object> project;
        final String to;
        final List<Dependencies.Selection> selections;
        final Map<String, String> replacements = new LinkedHashMap<>(), pins = new LinkedHashMap<>();
        final Map<Path, String> texts = new LinkedHashMap<>(), originals = new LinkedHashMap<>();
        final List<Map<String, Object>> applied = new ArrayList<>(), blockers = new ArrayList<>(), unchecked =
            new ArrayList<>();
        final List<Path> excluded = new ArrayList<>();
        /** Target dependency (version) -> the Loom version its jar was built with. */
        final Map<String, String> loomBuilt = new LinkedHashMap<>();

        Setup(Build build, Map<String, Object> project, String to, List<Dependencies.Selection> selections) {
            this.build = build;
            this.project = project;
            this.to = to;
            this.selections = selections;
        }

        String text(Path file) throws IOException {
            return texts.containsKey(file) ? texts.get(file) : Files.readString(file);
        }

        void replace(Path file, String from, String to) throws IOException {
            String text = text(file);
            int at = text.indexOf(from);
            if (at < 0) throw new IOException("Cannot locate an unambiguous setting in " + file + ": " + from);
            if (text.indexOf(from, at + 1) >= 0) throw new IOException(
                "Multiple matching settings in " + file + "; resolve their owner first: " + from
            );
            originals.putIfAbsent(file, text);
            texts.put(file, text.substring(0, at) + to + text.substring(at + from.length()));
        }

        /** Replace a whole file's content; the old text stays the edit's before for preview and undo. */
        void rewrite(Path file, String content) throws IOException {
            originals.putIfAbsent(file, text(file));
            texts.put(file, content);
        }

        /** Track a file the port creates; its before is null, so preview shows it and undo deletes it. */
        void createFile(Path file, String content) throws IOException {
            if (texts.containsKey(file)) throw new IOException("Duplicate scaffold write to " + file);
            if (Files.exists(file)) throw new IOException(
                "Scaffold would overwrite " + file + "; rewrite it instead of creating it"
            );
            texts.put(file, content);
        }

        List<Edit> edits() {
            List<Edit> edits = new ArrayList<>();
            for (Path file : texts.keySet()) if (!Objects.equals(originals.get(file), texts.get(file))) edits.add(
                new Edit(file, originals.get(file), texts.get(file))
            );
            return edits;
        }
    }

    static Setup plan(Build build, Map<String, Object> model, String to) throws Exception {
        return plan(build, model, to, List.of());
    }

    static Setup plan(Build build, Map<String, Object> model, String to, List<Dependencies.Selection> selections)
        throws Exception {
        List<Object> projects = Json.array(model.get("projects"));
        // A multi-module mod on one loader (Cardinal Components' modules, owo-lib and its sentinel) keeps its versions in
        // the shared gradle.properties, so the root project's setup retargets every module.
        // Plain helper projects with no loader (Twilight Forest's :tf-asm) are not mods to retarget.
        List<Object> modProjects = projects.stream().filter(p -> !Support.loaders(Json.object(p)).isEmpty()).toList();
        Set<Set<String>> loaderSets = new HashSet<>();
        for (Object p : modProjects) loaderSets.add(Support.loaders(Json.object(p)));
        Object root = modProjects.stream().filter(p -> ":".equals(Json.object(p).get("path"))).findFirst().orElse(null);
        boolean oneLoader = loaderSets.size() == 1 && loaderSets.iterator().next().size() == 1 && (root != null || modProjects.size() == 1);
        if (projects.size() != 1 && !oneLoader) throw new IOException(
            "Automatic target setup needs one selected project; choose --project."
        );
        var project = Json.object(projects.size() == 1 ? projects.get(0) : modProjects.size() == 1 ? modProjects.get(0) : root);
        Set<String> loaders = Support.loaders(project);
        // A NeoForge build (ModDevGradle or NeoGradle applied) that also lists a Forge artifact among its dependencies is NeoForge.
        String appliedPlugins = String.valueOf(project.get("plugins")).toLowerCase(Locale.ROOT);
        if (loaders.equals(Set.of("forge", "neoforge")) && appliedPlugins.matches("(?s).*(moddev|net\\.neoforged\\.gradle).*")) loaders = Set.of("neoforge");
        if (!loaders.equals(Set.of("fabric")) && !loaders.equals(Set.of("neoforge"))) throw new IOException(
            "Automatic target dependency setup requires one Fabric or NeoForge project; found " +
            loaders +
            ". Set this loader's target dependencies in the source build, then rerun port --from OLD --to NEW --fix."
        );
        Setup setup = new Setup(build, project, to, selections);
        var pins = new LinkedHashMap<>(Json.object(project.get("pins")));
        for (var pin : pins.entrySet()) if (pin.getValue() != null) setup.pins.put(
            pin.getKey(),
            pin.getValue().toString()
        );
        for (String key : List.of("minecraft_version", "minecraftVersion", "exactMinecraftVersion", "mc_version", "mcVersion")) if (
            pins.containsKey(key)
        ) setup.replacements.put(key, to);
        if (setup.replacements.isEmpty()) throw new IOException(
            "Minecraft is pinned inside build logic; update its owning setting, then rerun port."
        );
        if (loaders.equals(Set.of("neoforge"))) {
            // RetroFuturaGradle serves 1.7.10, which NeoForge never published for: a cross-architecture
            // port swaps the whole build for an RFG scaffold instead of resolving NeoForge artifacts.
            if (to.equals("1.7.10")) return TargetForge.legacyForgeScaffold(build, setup, pins, to);
            String resolved = TargetNeoForge.neoForgeVersion(build, to);
            boolean owned = false;
            for (String key : List.of("neo_version", "neoforge_version", "neoForgeVersion", "neoforgeVersion")) if (
                pins.containsKey(key)
            ) {
                setup.replacements.put(key, resolved);
                owned = true;
            }
            if (!owned) throw new IOException(
                "No NeoForge version pin (neo_version/neoforge_version) in this project; the build would keep its old NeoForge and Minecraft jars. Add or expose that property, then rerun port."
            );
            // Mod metadata declares an accepted game range; leaving it on the old version makes NeoForge refuse the mod at runtime.
            if (pins.containsKey("minecraft_version_range")) setup.replacements.put(
                "minecraft_version_range",
                "[" + to + "]"
            );
            for (String key : List.of("neo_version_range", "neoforge_version_range")) if (
                pins.containsKey(key)
            ) setup.replacements.put(key, "[" + resolved + ",)");
            TargetManifest.literalGameRange(setup, to);
            // A multi-loader build keeps Fabric's versions beside NeoForge's: the sibling project must follow the same game.
            try {
                TargetFabric.fabricPins(setup, build, pins, to);
                // The Fabric project's Loom follows the jars its Fabric API was built with, as in a Fabric port.
                if (!setup.loomBuilt.isEmpty()) TargetFabric.loomFollows(setup, build, to);
                TargetFabric.unobfuscatedFabric(setup, build, to);
            } catch (IOException sibling) {
                setup.unchecked.add(Json.map("artifact", "Fabric (sibling project)", "version", to, "reason", sibling.getMessage()));
            }
            TargetForge.forgeSibling(setup, build, pins, to);
            Dependencies.resolve(setup, "neoforge");
            siblingDependencies(setup, build, modProjects, project, to, selections);
            applyReplacements(setup, build, pins);
            TargetNeoForge.neoGradleFollows(setup, build, to);
            TargetNeoForge.modDevFollows(setup, build, to);
            TargetFabric.noAdditionalRuntimeClasspath(setup, build, to);
            TargetNeoForge.neoFormFollows(setup, build, to);
            TargetGradle.toolchain(setup, build, to);
            TargetGradle.gradleForJava25(setup, build, to);
            TargetGradle.buildDslFollows(setup, build);
            TargetNeoForge.runTypeRename(setup, build, to);
            TargetParchment.parchmentFollows(setup, build, to);
            TargetNeoForge.unitTestsInFml(setup, build, to, resolved);
            setup.applied.add(
                Json.map(
                    "artifact",
                    "net.neoforged:neoforge",
                    "to",
                    resolved,
                    "identity",
                    "resolved from maven.neoforged.net",
                    "gameVersion",
                    to,
                    "evidence",
                    "NeoForge " + resolved + " publishes for " + to
                )
            );
            return setup;
        }
        TargetFabric.fabricPins(setup, build, pins, to);
        // A multi-loader build keeps NeoForge's version beside Fabric's: the sibling project must follow the same game.
        TargetNeoForge.neoForgeSibling(setup, build, pins, to);
        TargetForge.forgeSibling(setup, build, pins, to);
        TargetNeoForge.neoFormFollows(setup, build, to);
        // A multi-loader build's common or NeoForge module set up by ModDevGradle needs one that knows the target game
        // (Bookshelf 1.21.1's ModDevGradle 2.0.30-beta never adds Mojang's library server for 1.21.2's jtracy).
        TargetNeoForge.modDevFollows(setup, build, to);
        Dependencies.resolve(setup);
        siblingDependencies(setup, build, modProjects, project, to, selections);
        applyReplacements(setup, build, pins);
        TargetFabric.loomFollows(setup, build, to);
        TargetFabric.unobfuscatedFabric(setup, build, to);
        TargetGradle.toolchain(setup, build, to);
        TargetGradle.gradleForJava25(setup, build, to);
        // After every step that can raise Gradle: what it removed depends on the Gradle the build ends on (MidnightLib's
        // archivesBaseName was left for Gradle 8, and Java 25 then brought Gradle 9).
        TargetGradle.buildDslFollows(setup, build);
        TargetParchment.parchmentFollows(setup, build, to);
        TargetManifest.manifest(setup);
        return setup;
    }

    /**
     * A multi-loader build ported through one loader's project still builds the other loaders' modules: their own
     * libraries move to the target too (TorchMaster's Fabric module kept owo-lib 0.12.11+1.21, whose 1.21 jar Loom then
     * refused on 26.3). Each other single-loader mod project is resolved like the selected one, against the same pending
     * edits, and its changes, blockers and unchecked entries join the port's.
     */
    static void siblingDependencies(Setup setup, Build build, List<Object> modProjects, Map<String, Object> selected, String to,
        List<Dependencies.Selection> selections) throws Exception {
        // A port selected to one project read only that project; the others are read once from a model of every project.
        if (modProjects.size() <= 1 && !build.selected.isBlank()) {
            try {
                modProjects = Json.array(build.allProjects().model(false, true).get("projects"))
                    .stream()
                    .filter(p -> !Support.loaders(Json.object(p)).isEmpty())
                    .toList();
            } catch (Exception unreadable) {
                setup.unchecked.add(Json.map("artifact", "the other loaders' projects", "version", to, "reason", "the build could not be read with every project: " + unreadable.getMessage()));
                return;
            }
        }
        for (Object item : modProjects) {
            var sibling = Json.object(item);
            if (sibling == selected || Objects.equals(sibling.get("path"), selected.get("path"))) continue;
            Set<String> loaders = Support.loaders(sibling);
            if (loaders.size() != 1 || !(loaders.contains("fabric") || loaders.contains("neoforge"))) continue;
            Setup view = new Setup(build, sibling, to, selections);
            view.texts.putAll(setup.texts);
            view.originals.putAll(setup.originals);
            view.pins.putAll(setup.pins);
            // The sibling's own properties (Waystones' journeymap_api_version), so a version it moves can be written where
            // it is defined.
            for (var pin : Json.object(sibling.get("pins")).entrySet()) if (pin.getValue() != null) {
                view.pins.putIfAbsent(pin.getKey(), pin.getValue().toString());
                setup.pins.putIfAbsent(pin.getKey(), pin.getValue().toString());
            }
            view.replacements.putAll(setup.replacements);
            try {
                Dependencies.resolve(view, loaders.iterator().next());
            } catch (IOException unresolved) {
                setup.unchecked.add(Json.map("artifact", "dependencies of project " + view.project.get("path"), "version", to, "reason", String.valueOf(unresolved.getMessage())));
                continue;
            }
            setup.texts.putAll(view.texts);
            for (var original : view.originals.entrySet()) setup.originals.putIfAbsent(original.getKey(), original.getValue());
            for (var r : view.replacements.entrySet()) {
                if (setup.pins.containsKey(r.getKey())) setup.replacements.putIfAbsent(r.getKey(), r.getValue());
                else setup.unchecked.add(
                    Json.map(
                        "artifact",
                        r.getKey(),
                        "version",
                        r.getValue(),
                        "reason",
                        "a library of project " + sibling.get("path") + " moves, but its property has no gradle.properties owner mmt can find"
                    )
                );
            }
            setup.applied.addAll(view.applied);
            setup.blockers.addAll(view.blockers);
            setup.unchecked.addAll(view.unchecked);
            setup.excluded.addAll(view.excluded);
            for (var built : view.loomBuilt.entrySet()) setup.loomBuilt.putIfAbsent(built.getKey(), built.getValue());
        }
    }

    /** Removes each whole statement that starts where the pattern matches, through its balanced brackets. */
    static String removeStatements(String text, Pattern start) {
        StringBuilder out = new StringBuilder();
        int at = 0;
        Matcher m = start.matcher(text);
        while (m.find(at)) {
            int end = m.start(), depth = 0;
            boolean string = false;
            char quote = 0;
            for (; end < text.length(); end++) {
                char c = text.charAt(end);
                if (string) {
                    if (c == '\\') end++;
                    else if (c == quote) string = false;
                    continue;
                }
                if (c == '"' || c == '\'') {
                    string = true;
                    quote = c;
                } else if (c == '(' || c == '{' || c == '[') depth++;
                else if (c == ')' || c == '}' || c == ']') depth--;
                else if (c == '\n' && depth <= 0) break;
            }
            out.append(text, at, m.start());
            at = Math.min(text.length(), end + 1);
        }
        return out.append(text.substring(at)).toString();
    }

    /** The newest final release of one major line in a maven-metadata.xml (pre-releases only when the line has nothing else). */
    static String newestInLine(String xml, String major) {
        String release = null, any = null;
        Matcher version = Pattern.compile("<version>(" + Pattern.quote(major) + "\\.[^<]+)</version>").matcher(xml);
        while (version.find()) {
            String v = version.group(1);
            if (any == null || Versions.compare(v, any) > 0) any = v;
            if (!v.matches(".*-(?:beta|alpha|rc|SNAPSHOT).*") && (release == null || Versions.compare(v, release) > 0)) release = v;
        }
        return release != null ? release : any;
    }

    static void setProperty(Setup setup, Path properties, String key, String value) throws IOException {
        // The whole value: a range such as ">=1.21.4 <1.21.5" is one property (Trash Cans' minecraft_dependency).
        Matcher line = Pattern.compile("(?m)^([ \\t]*" + Pattern.quote(key) + "[ \\t]*=[ \\t]*)([^\\r\\n]*?)[ \\t]*$").matcher(
            setup.text(properties)
        );
        if (!line.find()) throw new IOException(
            properties.getFileName() + " has no " + key + " to set to " + value + "; set it by hand, then rerun port."
        );
        if (!line.group(2).equals(value)) setup.replace(properties, line.group(), line.group(1) + value);
    }

    private static void applyReplacements(Setup setup, Build build, Map<String, Object> pins) throws IOException {
        for (var pin : setup.replacements.entrySet()) {
            List<Path> owners = new ArrayList<>();
            for (Path file : build.project.inputs()) if (
                file.getFileName().toString().equals("gradle.properties") &&
                Objects.equals(ProjectModel.properties(file).getProperty(pin.getKey()), setup.pins.get(pin.getKey()))
            ) owners.add(file);
            if (owners.size() > 1) {
                // Stonecutter-style repos keep one property file per node; the selected node owns its own copy.
                Path directory = Path.of(Json.text(setup.project, "directory", "")).toAbsolutePath().normalize();
                List<Path> own = owners
                    .stream()
                    .filter(p -> p.toAbsolutePath().normalize().startsWith(directory))
                    .toList();
                if (own.size() == 1) owners = own;
            }
            if (owners.size() != 1) throw new IOException(
                "Cannot choose an unambiguous gradle.properties owner for " + pin.getKey() + ": " + owners
            );
            Path owner = owners.get(0);
            String text = setup.text(owner);
            Matcher match = Pattern.compile(
                "(?m)^([ \\t]*" + Pattern.quote(pin.getKey()) + "[ \\t]*[=:][ \\t]*)[^\\r\\n]*"
            ).matcher(text);
            if (!match.find()) throw new IOException("Cannot find property " + pin.getKey() + " in " + owner);
            String prefix = match.group(1);
            int start = match.start(), end = match.end();
            if (match.find()) throw new IOException("Duplicate property " + pin.getKey() + " in " + owner);
            setup.replace(owner, text, text.substring(0, start) + prefix + pin.getValue() + text.substring(end));
        }
    }

    /** Replace the value of one property line, keeping its original separator and spacing. */
    static String replace(String text, String key, String value) throws IOException {
        Matcher match = Pattern.compile("(?m)^([ \\t]*" + Pattern.quote(key) + "[ \\t]*[=:][ \\t]*)[^\\r\\n]*").matcher(
            text
        );
        if (!match.find()) throw new IOException("Cannot edit property " + key + " without rewriting build logic");
        int start = match.start(), end = match.end();
        String replacement = match.group(1) + value;
        if (match.find()) throw new IOException("Duplicate property " + key + "; resolve its owner first");
        return text.substring(0, start) + replacement + text.substring(end);
    }

    private static String propertyText(String text, String key) throws IOException {
        Matcher match = Pattern.compile("(?m)^[ \\t]*" + Pattern.quote(key) + "[ \\t]*[=:][ \\t]*[^\\r\\n]*").matcher(
            text
        );
        if (!match.find()) throw new IOException("Cannot find property " + key);
        return match.group();
    }

    static void apply(List<Edit> edits) throws IOException {
        for (Edit edit : edits) {
            // A null before is a file the port creates: it must be absent, not equal to anything.
            if (edit.before() == null) {
                if (Files.exists(edit.file())) throw new IOException(
                    "Target configuration changed during setup: " + edit.file()
                );
                continue;
            }
            if (!Files.readString(edit.file()).equals(edit.before())) throw new IOException(
                "Target configuration changed during setup: " + edit.file()
            );
        }
        requireWritable(edits);
        write(edits);
    }

    static void requireWritable(List<Edit> edits) throws IOException {
        // Every file is checked before the first is written: a read-only one stops the port with nothing changed.
        List<String> locked = new ArrayList<>();
        for (Edit edit : edits) {
            // A created file checks its parent: the file itself cannot be writable yet.
            Path writable = edit.before() == null ? edit.file().toAbsolutePath().getParent() : edit.file();
            if (!Files.isWritable(writable)) locked.add(edit.file().toString());
        }
        if (!locked.isEmpty()) throw new IOException(
            "Cannot write " +
            String.join(", ", locked) +
            ": " +
            (locked.size() == 1 ? "it is" : "they are") +
            " read-only. Clear the read-only flag (Windows: file Properties; macOS and Linux: chmod u+w), then rerun. Nothing was changed."
        );
    }

    static void write(List<Edit> edits) throws IOException {
        for (Edit edit : edits) {
            if (edit.before() == null && edit.file().getParent() != null) Files.createDirectories(
                edit.file().getParent()
            );
            Files.writeString(edit.file(), edit.after());
        }
    }

    static String fetch(Build build, String url) throws Exception {
        Path cache = build.project
            .state()
            .resolve("metadata/" + Fingerprints.bytes(url.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".json");
        if (
            Files.isRegularFile(cache) &&
            System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis() < 3600000
        ) return Files.readString(cache);
        var request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "minecraft-modding-tools/local-port-tool")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        HttpResponse<String> response;
        try {
            response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException transport) {
            throw unreachable(url, transport);
        }
        int status = response.statusCode();
        if (status == 429 || status >= 500) throw new LookupFailure(
            "Target dependency lookup failed (HTTP " + status + ", the server did not answer the question): " + url,
            null
        );
        if (status != 200) {
            var version = java.util.regex.Pattern.compile(
                "meta\\.fabricmc\\.net/v2/versions/(?:loader|yarn|intermediary)/([^/?]+)"
            ).matcher(url);
            if (version.find() && (status == 400 || status == 404)) throw new IOException(
                "Fabric has no release for Minecraft " +
                version.group(1) +
                " (" +
                url +
                " answered HTTP " +
                status +
                "). Check the version: it must be a released Minecraft version Fabric supports, such as 1.21.6 or 26.3. Nothing was changed."
            );
            throw new IOException("Target dependency lookup failed (HTTP " + status + "): " + url);
        }
        Files.createDirectories(cache.getParent());
        Files.writeString(cache, response.body());
        return response.body();
    }

    /** A network error names the host and the real exception, never a bare "null". */
    static LookupFailure unreachable(String url, IOException transport) {
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException invalid) {
            host = url;
        }
        String detail = transport.getMessage() == null || transport.getMessage().isBlank()
            ? transport.getClass().getSimpleName()
            : transport.getClass().getSimpleName() + ": " + transport.getMessage();
        return new LookupFailure("Could not reach " + host + " (" + detail + "): " + url, transport);
    }

    /** Download one published artifact into the project's metadata cache; returns its local path. */
    static Path download(Build build, String url) throws Exception {
        Path cache = build.project
            .state()
            .resolve(
                "metadata/downloads/" +
                Fingerprints.bytes(url.getBytes(java.nio.charset.StandardCharsets.UTF_8)) +
                ".jar"
            );
        if (Files.isRegularFile(cache)) return cache;
        var request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "minecraft-modding-tools/local-port-tool")
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build();
        HttpResponse<InputStream> response;
        try {
            response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException transport) {
            throw unreachable(url, transport);
        }
        if (response.statusCode() != 200) throw new IOException(
            "Target artifact download failed (HTTP " + response.statusCode() + "): " + url
        );
        Files.createDirectories(cache.getParent());
        try (var input = response.body()) {
            Files.copy(input, cache, StandardCopyOption.REPLACE_EXISTING);
        }
        return cache;
    }
}
