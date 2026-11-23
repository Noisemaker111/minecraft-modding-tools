import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.*;

/** A mod's mixins in source: targets renamed or removed with the game's, accessors written for members the update closed, and the mixin config kept in step. */
final class PortMixins {

    final Port port;

    PortMixins(Port port) {
        this.port = port;
    }

    /**
     * The target's name for a method a mixin targets by its old name: Mixin says it found no 'initTabNavigation' in
     * OptionsScreen, and the map renamed initTabNavigation to refreshWidgetPositions on Screen, OptionsScreen's supertype.
     * Null unless exactly one new name fits the owner or one of its supertypes in the target.
     */
    String mixinTargetRename(String owner, String name) throws Exception {
        if (port.maps.mapFile == null || !Files.isRegularFile(port.maps.mapFile)) return null;
        if (port.maps.mapMethods == null) {
            port.maps.mapMethods = new HashMap<>();
            var map = Json.read(port.maps.mapFile);
            for (String list : List.of("methods", "methodsMoved", "signatures")) for (Object o : Json.array(map.get(list))) {
                var m = Json.object(o);
                String from = Json.text(m, "from", ""), to = Json.text(m, "to", "");
                if (!from.equals(to)) port.maps.mapMethods
                    .computeIfAbsent(from, k -> new ArrayList<>())
                    .add(new String[] { JavaText.simpleName(Json.text(m, "owner", "")), to });
            }
        }
        List<String[]> candidates = port.maps.mapMethods.get(name);
        if (candidates == null) return null;
        if (port.hierarchy.supertypeIndex == null) port.hierarchy.supertypeIndex = new Explain.Index(Port.classpath(port.model));
        Set<String> owners = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(owner));
        while (!queue.isEmpty()) {
            String next = queue.poll();
            if (!owners.add(JavaText.simpleName(next.replace('/', '.')))) continue;
            Explain.ClassInfo info = port.hierarchy.supertypeIndex.info(next);
            if (info == null) continue;
            if (info.superName() != null) queue.add(info.superName());
            queue.addAll(info.interfaces());
        }
        Set<String> names = new HashSet<>();
        for (String[] c : candidates) if (owners.contains(c[0])) names.add(c[1]);
        return names.size() == 1 ? names.iterator().next() : null;
    }

    /** The real Mixin check for the ported tree; a check that cannot run is an unchecked result, never a pass. */
    Map<String, Object> mixinCheck(Map<String, Object> model, Mmt.Options options) {
        try {
            return new MixinCheck(port.build, model).run(options);
        } catch (Exception failure) {
            String reason = "The Mixin check could not run: " + failure.getMessage();
            return Json.map(
                "ok",
                false,
                "checked",
                false,
                "tier",
                "mixins-unchecked",
                "reason",
                reason,
                "details",
                reason,
                "gameVerified",
                false
            );
        }
    }

    static String mixinSummary(Map<String, Object> mixins) {
        if (mixins == null) return "mixins unchecked";
        if ("no-mixins".equals(mixins.get("tier"))) return "the mod declares no mixins";
        return "Mixin applied its mixins against the target game (" + mixins.get("details") + ")";
    }

    /** What the mixin audit actually looked at, from its summary line: never "checked" when nothing was. */
    static String auditSummary(Path log) throws IOException {
        String summary = "";
        if (log != null && Files.exists(log)) for (String line : Port.logText(log).lines().toList()) if (
            line.startsWith("# ")
        ) summary = line.substring(2).strip();
        if (summary.isEmpty()) return "the mixin audit passed without a summary";
        if (summary.startsWith("0 mixins")) return "no mixins to audit";
        Matcher skipped = Pattern.compile("(\\d+) targets not in the jar").matcher(summary);
        return (
            "mixin targets checked (" +
            summary.replaceFirst(",?\\s*\\d+ targets not in the jar", "") +
            ")" +
            (skipped.find()
                    ? "; " +
                    skipped.group(1) +
                    " mixin target(s) are in no jar on the classpath and were not checked (see the mixin-audit log)"
                    : "")
        );
    }

    /**
     * A mapping change can delete one target of a multi-target mixin. The annotation processor
     * names it and reports an empty match, so remove only that dead name and keep the rest.
     */
    void removeDeadMixinTargets(Path log) throws Exception {
        String errors = Port.logText(log);
        Matcher reported = Pattern.compile(
            "(?m)^\\s*" + Port.FILE + ":(\\d+): error: Multi-target reference conflict for @\\w+ target (\\w+): \\[\\]"
        ).matcher(errors);
        Set<String> repaired = new HashSet<>();
        while (reported.find()) {
            Path file = Path.of(reported.group(1));
            if (!file.isAbsolute()) file = port.build.project.root.resolve(file);
            file = file.normalize();
            if (!file.startsWith(port.build.project.root) || !Files.isRegularFile(file)) continue;
            String target = reported.group(3);
            if (!repaired.add(file + ":" + target)) continue;
            String before = Files.readString(file), after = removeDeadTarget(before, target);
            Files.writeString(file, after);
            Path lessons = port.build.project.state().resolve("lessons.jsonl");
            Files.writeString(
                lessons,
                Json.write(
                    Json.map(
                        "rule",
                        "mixin-removed-target",
                        "target",
                        target,
                        "file",
                        port.build.project.root.relativize(file).toString().replace('\\', '/'),
                        "to",
                        port.targetVersion
                    )
                ) +
                "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
            port.build.report.stages.add(
                Json.map(
                    "name",
                    "mixin-target-removed",
                    "target",
                    target,
                    "file",
                    file.toString(),
                    "evidence",
                    "mixin annotation processor reported no target for this mapping"
                )
            );
        }
    }

    /** Remove one quoted method name from a single-line multi-target mixin annotation. */
    static String removeDeadTarget(String text, String target) throws IOException {
        Matcher annotation = Pattern.compile("@(\\w+)\\s*\\(((?:[^()\\n]|\\([^()\\n]*\\))*)\\)").matcher(text);
        while (annotation.find()) {
            Matcher methods = Pattern.compile("method\\s*=\\s*\\{([^}]*)\\}").matcher(annotation.group(2));
            if (!methods.find()) continue;
            List<String> names = new ArrayList<>();
            for (String item : methods.group(1).split(",")) names.add(item.trim());
            String quoted = "\"" + target + "\"";
            if (names.size() < 2 || !names.contains(quoted)) continue;
            names.remove(quoted);
            int base = annotation.start(2);
            String replacement =
                annotation.group().substring(0, base + methods.start(1) - annotation.start()) +
                String.join(", ", names) +
                annotation.group().substring(base + methods.end(1) - annotation.start());
            return text.substring(0, annotation.start()) + replacement + text.substring(annotation.end());
        }
        throw new IOException(
            "The annotation processor reported a dead target " +
            target +
            ", but no single-line multi-target mixin declaration contains it; repair it by hand"
        );
    }

    /**
     * Repairs that Mixin's own failure messages fully determine, in the mixin's source file:
     * a target method pinned by a descriptor that no longer exists is named by its name alone, and a handler whose
     * descriptor Mixin rejects ("Expected (A;B;C) but found (A;C)") gets the parameters it now needs, keeping its own.
     * True when a file changed; the port then compiles again and Mixin checks the result.
     */
    boolean repairMixins(Map<String, Object> mixins) throws IOException {
        boolean changed = false;
        Object failures = mixins.get("failures");
        if (!(failures instanceof List<?> list)) return false;
        List<Object> repaired = new ArrayList<>();
        for (Object item : list) {
            var failure = Json.object(item);
            Path file = port.build.project.root.resolve(Json.text(failure, "file", "")).normalize();
            // A mixin in another project of the build (an Architectury :common) is found by its class name.
            if (Json.text(failure, "file", "").isEmpty() && !Json.text(failure, "mixin", "").isEmpty()) {
                String tail = Json.text(failure, "mixin", "").replace('.', '/') + ".java";
                for (Path source : Port.sources(port.build.project)) if (source.toString().replace('\\', '/').endsWith("/" + tail)) {
                    file = source;
                    failure = new LinkedHashMap<>(failure);
                    failure.put("file", port.build.project.root.relativize(source).toString());
                    break;
                }
            }
            if (
                Json.text(failure, "file", "").isEmpty() ||
                !file.startsWith(port.build.project.root) ||
                !Files.isRegularFile(file)
            ) continue;
            String original = Files.readString(file), text = repairMixinSource(
                original,
                Json.text(failure, "message", "")
            );
            if (text.equals(original)) text = renameMixinTarget(original, Json.text(failure, "message", ""));
            if (!text.equals(original)) {
                Files.writeString(file, text);
                changed = true;
                repaired.add(Json.map("mixin", failure.get("mixin"), "file", failure.get("file")));
            }
        }
        if (changed) port.build.report.stages.add(Json.map("name", "mixin-repair", "repaired", repaired));
        return changed;
    }

    /** A mixin target the game renamed, renamed in the mixin's method strings, from Mixin's words and the route map. */
    String renameMixinTarget(String text, String message) throws IOException {
        Matcher missing = Pattern.compile("could not find any targets matching '(\\w+)(?:\\([^']*)?' in ([\\w/$]+)").matcher(message);
        if (!missing.find()) return text;
        String to;
        try {
            to = mixinTargetRename(missing.group(2), missing.group(1));
        } catch (Exception unread) {
            return text;
        }
        if (to == null) return text;
        port.rulesApplied.putIfAbsent("mixin-target-rename", List.of());
        return text.replaceAll("\"" + Pattern.quote(missing.group(1)) + "(?:\\([^\"]*\\)[^\"]*)?\"", Matcher.quoteReplacement("\"" + to + "\""));
    }

    static String repairMixinSource(String text, String message) {
        Matcher missing = Pattern.compile("could not find any targets matching '(\\w+)(\\([^']*)'").matcher(message);
        if (missing.find()) {
            String pinned = "\"" + missing.group(1) + missing.group(2) + "\"";
            if (text.contains(pinned)) return text.replace(pinned, "\"" + missing.group(1) + "\"");
        }
        Matcher descriptor = Pattern.compile(
            "->@\\w+::(\\w+)\\([^)]*\\)[^!]*! Expected \\(([^)]*)\\)\\S* but found \\(([^)]*)\\)"
        ).matcher(message);
        if (!descriptor.find()) return text;
        List<String> expected = descriptorTypes(descriptor.group(2)), found = descriptorTypes(descriptor.group(3));
        Matcher handler = Pattern.compile("\\b" + Pattern.quote(descriptor.group(1)) + "\\s*\\(").matcher(text);
        while (handler.find()) {
            List<int[]> params = JavaText.arguments(text, handler.end() - 1);
            int close = params.isEmpty() ? text.indexOf(')', handler.end()) : params.get(params.size() - 1)[1];
            if (
                close < 0 ||
                !text.substring(close + 1).matches("(?s)\\s*(?:throws[^{]*)?\\{.*") ||
                params.size() != found.size()
            ) continue;
            List<String> written = new ArrayList<>();
            Set<String> imports = new LinkedHashSet<>();
            int next = 0;
            for (String type : expected) {
                if (next < found.size() && found.get(next).equals(type)) {
                    written.add(text.substring(params.get(next)[0], params.get(next)[1]).strip());
                    next++;
                    continue;
                }
                String java = javaType(type, imports);
                if (java == null) return text;
                written.add(
                    java +
                    " mmt$" +
                    Character.toLowerCase(JavaText.simpleName(java).charAt(0)) +
                    JavaText.simpleName(java).substring(1).replaceAll("\\W", "") +
                    written.size()
                );
            }
            if (next != found.size()) return text;
            String out = text.substring(0, handler.end()) + String.join(", ", written) + text.substring(close);
            for (String name : imports) out = JavaText.addImport(out, name);
            return out;
        }
        return text;
    }

    /** A method descriptor's parameter list, one type descriptor each. */
    static List<String> descriptorTypes(String parameters) {
        List<String> types = new ArrayList<>();
        Matcher type = Pattern.compile("\\[*(?:L[^;]+;|[ZBCSIJFD])").matcher(parameters);
        while (type.find()) types.add(type.group());
        return types;
    }

    /** The Java source type for a descriptor (Item$TooltipContext as Item.TooltipContext), adding the import it needs; null for none. */
    static String javaType(String descriptor, Set<String> imports) {
        int dims = 0;
        while (dims < descriptor.length() && descriptor.charAt(dims) == '[') dims++;
        String base = descriptor.substring(dims), java =
            switch (base) {
                case "Z" -> "boolean";
                case "B" -> "byte";
                case "C" -> "char";
                case "S" -> "short";
                case "I" -> "int";
                case "J" -> "long";
                case "F" -> "float";
                case "D" -> "double";
                default -> null;
            };
        if (java == null) {
            if (!base.startsWith("L") || !base.endsWith(";")) return null;
            String binary = base.substring(1, base.length() - 1).replace('/', '.'), outer = binary.contains("$")
                ? binary.substring(0, binary.indexOf('$'))
                : binary;
            if (!outer.startsWith("java.lang.") || outer.substring(10).contains(".")) imports.add(outer);
            java = binary.substring(outer.lastIndexOf('.') + 1).replace('$', '.');
        }
        return java + "[]".repeat(dims);
    }

    /**
     * A mixin that extends a game class must declare a constructor, and Mixin never runs it (the mixin's own constructors
     * are dropped when it is applied). When the game changes that class's constructors (1.21.2: Level, MinecartChest), the
     * super call stops compiling for no reason the game cares about: its arguments become typed placeholders (null for an
     * object, zero or false otherwise) for a constructor the target class has, the one with fewest parameters. Only in
     * files annotated @Mixin, only on a super(...) call javac reported. Returns the calls changed.
     */
    int mixinConstructors(Path log) throws Exception {
        Matcher reported = Pattern.compile(
            "(?m)^\\s*" + Port.FILE + ":(\\d+): error: constructor (\\w+) in class ([\\w.$]+)(?:<[^>]*>)? cannot be applied to given types"
        ).matcher(Port.logText(log));
        Map<Path, List<String[]>> sites = new LinkedHashMap<>();
        while (reported.find()) {
            Path file = Path.of(reported.group(1));
            if (!file.isAbsolute()) file = port.build.project.root.resolve(file);
            if (Files.isRegularFile(file)) sites.computeIfAbsent(file.normalize(), f -> new ArrayList<>()).add(new String[] { reported.group(2), reported.group(4) });
        }
        int changed = 0;
        for (var entry : sites.entrySet()) {
            String text = Files.readString(entry.getKey());
            if (!text.contains("@Mixin")) continue;
            List<String> imports = new ArrayList<>();
            Matcher imp = Pattern.compile("(?m)^import\\s+([\\w.]+)\\s*;").matcher(text);
            while (imp.find()) imports.add(imp.group(1));
            String after = text;
            for (String[] site : entry.getValue()) {
                if (port.hierarchy.supertypeIndex == null) port.hierarchy.supertypeIndex = new Explain.Index(Port.classpath(port.model));
                List<String> found = port.hierarchy.supertypeIndex.resolve(site[1].replaceAll(".*\\.", ""), imports);
                Explain.ClassInfo info = found.isEmpty() ? null : port.hierarchy.supertypeIndex.info(found.get(0));
                if (info == null) continue;
                Explain.Member ctor = null;
                for (Explain.Member m : info.members()) {
                    if (!m.method() || !m.name().equals("<init>") || (m.access() & 0x0002) != 0) continue;
                    if (ctor == null || placeholders(m.descriptor()).size() < placeholders(ctor.descriptor()).size()) ctor = m;
                }
                if (ctor == null) continue;
                int lineStart = 0;
                for (int i = 1; i < Integer.parseInt(site[0]); i++) lineStart = after.indexOf('\n', lineStart) + 1;
                Matcher superCall = Pattern.compile("\\bsuper\\s*\\(").matcher(after);
                if (!superCall.find(lineStart) || after.substring(lineStart, superCall.start()).contains("\n")) continue;
                int open = superCall.end() - 1, close = JavaText.closingParen(after, open);
                if (close < 0) continue;
                after = after.substring(0, open + 1) + String.join(", ", placeholders(ctor.descriptor())) + after.substring(close);
            }
            if (!after.equals(text)) {
                Files.writeString(entry.getKey(), after);
                changed++;
            }
        }
        if (changed > 0) port.rulesApplied.putIfAbsent("mixin-constructor-placeholders", List.of());
        return changed;
    }

    /** Typed placeholder arguments for a descriptor's parameters: (fully.Qualified) null, or the primitive's zero. */
    static List<String> placeholders(String descriptor) {
        List<String> out = new ArrayList<>();
        Matcher p = Pattern.compile("(\\[*)(?:L([^;]+);|([ZBCSIJFD]))").matcher(descriptor.substring(descriptor.indexOf('(') + 1, descriptor.indexOf(')')));
        while (p.find()) {
            if (!p.group(1).isEmpty() || p.group(2) != null) {
                String base = p.group(2) != null ? p.group(2).replace('/', '.').replace('$', '.') : switch (p.group(3)) {
                    case "Z" -> "boolean";
                    case "B" -> "byte";
                    case "C" -> "char";
                    case "S" -> "short";
                    case "I" -> "int";
                    case "J" -> "long";
                    case "F" -> "float";
                    default -> "double";
                };
                out.add("(" + base + "[]".repeat(p.group(1).length()) + ") null");
                continue;
            }
            out.add(switch (p.group(3)) {
                case "Z" -> "false";
                case "J" -> "0L";
                case "F" -> "0.0F";
                case "D" -> "0.0D";
                case "C" -> "(char) 0";
                case "B" -> "(byte) 0";
                case "S" -> "(short) 0";
                default -> "0";
            });
        }
        return out;
    }

    /** The accessor method that stands in for a removed getter or setter: mmt$getExhaustion for the field exhaustion. */
    static String accessorMethod(String kind, String field) {
        return "mmt$" + kind + Character.toUpperCase(field.charAt(0)) + field.substring(1);
    }

    /**
     * A getter or setter the game removed while keeping its private field (1.21.2's HungerManager.getExhaustion()): each
     * call on the given lines (every line when null) goes through the accessor mixin instead, as
     * ((HungerManagerAccessor) receiver).mmt$getExhaustion(). A call with no receiver is left for javac to report.
     */
    static String accessorCalls(String text, Map<String, Object> apply, String accessor, Set<Integer> lines) {
        String field = Json.text(apply, "field", ""), simple = JavaText.simpleName(accessor);
        Map<String, String> methods = new LinkedHashMap<>();
        for (String kind : List.of("get", "set")) if (!Json.text(apply, kind, "").isEmpty()) methods.put(
            Json.text(apply, kind, ""),
            accessorMethod(kind, field)
        );
        if (methods.isEmpty() || field.isEmpty()) return text;
        Matcher call = Pattern.compile("\\.\\s*(" + String.join("|", methods.keySet()) + ")\\s*\\(").matcher(text);
        StringBuilder out = new StringBuilder();
        int copied = 0, line = 1, counted = 0;
        while (call.find()) {
            for (; counted < call.start(); counted++) if (text.charAt(counted) == '\n') line++;
            if (lines != null && !lines.contains(line)) continue;
            int receiver = JavaText.receiverStart(text, call.start());
            if (receiver >= call.start() || receiver < copied) continue;
            out
                .append(text, copied, receiver)
                .append("((")
                .append(simple)
                .append(") ")
                .append(text, receiver, call.start())
                .append(").")
                .append(methods.get(call.group(1)))
                .append('(');
            copied = call.end();
            // The removed setter took another type than the field ("setOwnerUUID(UUID)" for an EntityReference): its
            // argument goes through the rule's setArgument, {value} standing for what the mod passed.
            String wrap = Json.text(apply, "setArgument", "");
            List<int[]> passed = JavaText.arguments(text, call.end() - 1);
            if (!wrap.isEmpty() && call.group(1).equals(Json.text(apply, "set", "")) && passed.size() == 1) {
                out.append(wrap.replace("{value}", text.substring(passed.get(0)[0], passed.get(0)[1]).strip()));
                copied = passed.get(0)[1];
            }
        }
        if (copied == 0) return text;
        return JavaText.addImport(out.append(text.substring(copied)).toString(), accessor);
    }

    /**
     * Runs an accessor rule on the errors it names: javac's "cannot find symbol" for the rule's getter or setter on its
     * owner class. Writes the accessor mixin into the package of the mod's main mixins config, lists it there, and sends
     * each failing call through it. False when no error is the rule's, or the mod has no mixins config to put it in
     * (then the error stays, with its repair guide).
     */
    boolean applyAccessor(Map<String, Object> rule, String errors) throws Exception {
        var apply = Json.object(rule.get("apply"));
        String owner = Json.text(apply, "owner", ""), ownerSimple = JavaText.simpleName(owner);
        Set<String> names = new HashSet<>();
        for (String kind : List.of("get", "set")) if (!Json.text(apply, kind, "").isEmpty()) names.add(
            Json.text(apply, kind, "")
        );
        String methods = names.stream().map(Pattern::quote).reduce((a, b) -> a + "|" + b).orElse("(?!)");
        // The receiver may be the owner or a subclass of it (a ZombieHorse for AbstractHorse's owner field): "receivers" lists them.
        String receivers = Json.text(apply, "receivers", Pattern.quote(ownerSimple));
        var sites = port.rules.reportedLines(
            errors,
            Pattern.compile(
                "cannot find symbol[^\\n]*\\n(?:[^\\n]*\\n)*?\\s*symbol:\\s+method (?:" +
                methods +
                ")\\([^\\n]*\\n\\s*location:[^\\n]*\\b(?:" +
                receivers +
                ")\\b"
            )
        );
        if (sites.isEmpty()) return false;
        Set<String> used = new HashSet<>();
        for (var lines : sites.values()) for (String symbol : lines.values()) used.add(
            symbol.equals(Json.text(apply, "get", "")) ? "get" : "set"
        );
        Path config = mainMixinConfig();
        if (config == null) config = newMixinConfig();
        if (config == null) {
            System.err.println(
                "degraded: " +
                Json.text(rule, "id", "accessor") +
                " needs a mixins config and this mod has none mmt can add (it adds one to NeoForge mods only)"
            );
            return false;
        }
        String mixinPackage = Json.text(Json.read(config), "package", "");
        Path folder = null;
        for (Path source : port.rules.ownedJava()) {
            Path parent = source.getParent();
            if (
                parent.endsWith(mixinPackage.replace('.', '/')) &&
                Files.readString(source).matches("(?s).*\\bpackage\\s+" + Pattern.quote(mixinPackage) + "\\s*;.*")
            ) {
                folder = parent;
                break;
            }
        }
        if (folder == null && newMixinFolder != null && mixinPackage.equals(newMixinPackage)) {
            folder = newMixinFolder;
            Files.createDirectories(folder);
        }
        if (folder == null) return false;
        String simple = ownerSimple + "Accessor";
        Path file = folder.resolve(simple + ".java");
        if (Files.exists(file) && !Files.readString(file).contains(Port.WRITTEN_MARK)) {
            simple = ownerSimple + "MmtAccessor";
            file = folder.resolve(simple + ".java");
        }
        String existing = Files.exists(file) ? Files.readString(file) : "";
        for (String kind : List.of("get", "set")) if (
            existing.contains(accessorMethod(kind, Json.text(apply, "field", "")) + "(")
        ) used.add(kind);
        port.journal.track(file);
        Files.writeString(file, accessorSource(mixinPackage, simple, apply, used));
        String listed = Files.readString(config), registered = registerMixin(listed, simple);
        if (!registered.equals(listed)) {
            port.journal.track(config);
            Files.writeString(config, registered);
        }
        for (var site : sites.entrySet()) {
            String original = Files.readString(site.getKey()), text = accessorCalls(
                original,
                apply,
                mixinPackage + "." + simple,
                site.getValue().keySet()
            );
            if (!text.equals(original)) Files.writeString(site.getKey(), port.rules.deferImports(site.getKey(), original, text));
        }
        return true;
    }

    /** The accessor mixin's source: a getter and, when the mod wrote the value, a setter on the owner's private field. */
    static String accessorSource(String mixinPackage, String simple, Map<String, Object> apply, Set<String> kinds) {
        String owner = Json.text(apply, "owner", ""), field = Json.text(apply, "field", ""), type = Json.text(
            apply,
            "type",
            ""
        );
        StringBuilder out = new StringBuilder(
            "package " +
            mixinPackage +
            ";\n\nimport " +
            owner +
            ";\nimport org.spongepowered.asm.mixin.Mixin;\nimport org.spongepowered.asm.mixin.gen.Accessor;\n\n"
        )
            .append("/** ")
            .append(Port.WRITTEN_MARK)
            .append(": ")
            .append(Json.text(apply, "why", JavaText.simpleName(owner) + " no longer exposes " + field + "."))
            .append(" */\n@Mixin(")
            .append(JavaText.simpleName(owner))
            .append(".class)\npublic interface ")
            .append(simple)
            .append(" {\n");
        if (kinds.contains("get")) out
            .append("    @Accessor(\"")
            .append(field)
            .append("\")\n    ")
            .append(type)
            .append(' ')
            .append(accessorMethod("get", field))
            .append("();\n");
        if (kinds.contains("set")) out
            .append("    @Accessor(\"")
            .append(field)
            .append("\")\n    void ")
            .append(accessorMethod("set", field))
            .append('(')
            .append(type)
            .append(" value);\n");
        return out.append("}\n").toString();
    }

    /** Lists a mixin class in a mixins config's common "mixins" array, adding the array when the config has none; formatting kept. */
    static String registerMixin(String config, String simple) {
        if (Pattern.compile("\"" + Pattern.quote(simple) + "\"").matcher(config).find()) return config;
        Matcher common = Pattern.compile("\"mixins\"\\s*:\\s*\\[(\\s*)").matcher(config);
        if (common.find()) {
            boolean empty = config.substring(common.end()).startsWith("]");
            String indent = common.group(1).isEmpty() ? "" : common.group(1);
            return (
                config.substring(0, common.end()) +
                "\"" +
                simple +
                "\"" +
                (empty ? "" : "," + (indent.isEmpty() ? " " : indent)) +
                config.substring(common.end())
            );
        }
        Matcher pkg = Pattern.compile("\"package\"\\s*:\\s*\"[^\"]*\"\\s*,?").matcher(config);
        if (!pkg.find()) return config;
        String head = config.substring(0, pkg.end());
        return (
            (head.endsWith(",") ? head : head + ",") +
            "\n  \"mixins\": [\"" +
            simple +
            "\"]" +
            (head.endsWith(",") ? "," : "") +
            config.substring(pkg.end())
        );
    }

    /** The mod's own required mixins config (a *.json with a "package"), preferring one marked required and with common mixins. */
    Path newMixinFolder;
    String newMixinPackage;

    /**
     * A NeoForge mod with no mixins gets a mixins config of its own the first time a port needs an accessor (Zombie
     * Apocalypse Addon's horse owner, 1.21.5): <mod id>.mmt.mixins.json beside its mods.toml's resources, the
     * package <the @Mod class's package>.mixin, and a [[mixins]] entry in META-INF/neoforge.mods.toml. Every file is
     * tracked, so undo removes the config and the entry again. Null for other loaders or when the mod class is not found.
     */
    Path newMixinConfig() throws Exception {
        Path toml = port.build.project.root.resolve("src/main/resources/META-INF/neoforge.mods.toml");
        if (!Files.isRegularFile(toml)) return null;
        String modPackage = null;
        Path modFolder = null;
        for (Path source : port.rules.ownedJava()) {
            String text = Files.readString(source);
            Matcher pkg = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(text);
            if (text.contains("@Mod(") && pkg.find()) {
                modPackage = pkg.group(1);
                modFolder = source.getParent();
                break;
            }
        }
        if (modPackage == null) return null;
        String tomlText = Files.readString(toml);
        Matcher id = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"").matcher(tomlText);
        String modId = id.find() ? id.group(1) : modPackage.substring(modPackage.lastIndexOf('.') + 1);
        Matcher template = Pattern.compile("^\\$\\{(\\w+)}$").matcher(modId);
        if (template.find()) {
            Path properties = port.build.project.root.resolve("gradle.properties");
            String value = Files.isRegularFile(properties)
                ? ProjectModel.properties(properties).getProperty(template.group(1))
                : null;
            modId = value != null ? value.strip() : modPackage.substring(modPackage.lastIndexOf('.') + 1);
        }
        String name = modId + ".mmt.mixins.json";
        Path config = toml.getParent().getParent().resolve(name);
        port.journal.track(config);
        Files.writeString(
            config,
            "{\n  \"required\": true,\n  \"minVersion\": \"0.8\",\n  \"package\": \"" +
            modPackage +
            ".mixin\",\n  \"compatibilityLevel\": \"JAVA_21\",\n  \"mixins\": [],\n  \"injectors\": {\n    \"defaultRequire\": 1\n  }\n}\n"
        );
        port.journal.track(toml);
        Files.writeString(
            toml,
            tomlText +
            (tomlText.endsWith("\n") ? "" : "\n") +
            "\n# " +
            Port.WRITTEN_MARK +
            ": the accessor mixins a port writes.\n[[mixins]]\nconfig=\"" +
            name +
            "\"\n"
        );
        newMixinPackage = modPackage + ".mixin";
        newMixinFolder = modFolder.resolve("mixin");
        port.build.report.stages.add(
            Json.map(
                "name",
                "mixin-config",
                "added",
                port.build.project.root.relativize(config).toString(),
                "package",
                newMixinPackage
            )
        );
        return config;
    }

    Path mainMixinConfig() throws IOException {
        List<Path> configs = new ArrayList<>();
        Files.walkFileTree(
            port.build.project.root,
            new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    return (
                            !dir.equals(port.build.project.root) &&
                            (ProjectModel.IGNORED.contains(dir.getFileName().toString()) ||
                                Files.exists(dir.resolve(".git")))
                        )
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    String name = file.getFileName().toString();
                    if (name.endsWith(".json") && name.contains("mixins") && !name.contains("refmap")) configs.add(
                        file
                    );
                    return FileVisitResult.CONTINUE;
                }
            }
        );
        Path best = null;
        int bestScore = -1;
        for (Path file : new TreeSet<>(configs)) {
            Map<String, Object> config;
            try {
                config = Json.read(file);
            } catch (IOException | IllegalArgumentException unreadable) {
                continue;
            }
            if (Json.text(config, "package", "").isEmpty()) continue;
            int score = (Boolean.FALSE.equals(config.get("required")) ? 0 : 2) + (config.containsKey("mixins") ? 1 : 0);
            if (score > bestScore) {
                best = file;
                bestScore = score;
            }
        }
        return best;
    }
}
