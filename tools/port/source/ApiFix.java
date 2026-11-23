import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import javax.tools.*;

/** Reviewed API migrations. Parse only: no target classes are loaded and no mod code is executed. */
final class ApiFix {

    record Edit(int start, int end, String text) {}

    final String source;
    final CompilationUnitTree unit;
    final SourcePositions positions;
    final List<TreePath> paths = new ArrayList<>();
    final List<Edit> edits = new ArrayList<>();

    ApiFix(Path file, String source) throws IOException {
        this.source = source;
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        JavaFileObject input = new SimpleJavaFileObject(file.toUri(), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignored) {
                return source;
            }
        };
        JavacTask task = (JavacTask) compiler.getTask(
            null,
            null,
            diagnostic -> {},
            List.of("-proc:none"),
            null,
            List.of(input)
        );
        unit = task.parse().iterator().next();
        positions = Trees.instance(task).getSourcePositions();
        collectPaths();
    }

    ApiFix(Path file, String source, CompilationUnitTree unit, SourcePositions positions) {
        this.source = source;
        this.unit = unit;
        this.positions = positions;
        collectPaths();
    }

    void collectPaths() {
        new TreePathScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void unused) {
                if (tree != null && getCurrentPath() != null) {
                    paths.add(new TreePath(getCurrentPath(), tree));
                }
                return super.scan(tree, unused);
            }
        }.scan(unit, null);
    }

    /** One compiler task for the whole repo: task setup and Trees cost once, not once per file. */
    static List<ApiFix> parseAll(List<Path> files, List<String> sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        List<JavaFileObject> inputs = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            String source = sources.get(i);
            inputs.add(new SimpleJavaFileObject(file.toUri(), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignored) {
                    return source;
                }
            });
        }
        JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostic -> {}, List.of("-proc:none"), null, inputs);
        List<ApiFix> out = new ArrayList<>();
        Iterator<? extends CompilationUnitTree> units = task.parse().iterator();
        SourcePositions positions = Trees.instance(task).getSourcePositions();
        for (int i = 0; i < files.size(); i++) out.add(new ApiFix(files.get(i), sources.get(i), units.next(), positions));
        return out;
    }

    int start(Tree t) {
        return (int) positions.getStartPosition(unit, t);
    }

    int end(Tree t) {
        return (int) positions.getEndPosition(unit, t);
    }

    String text(Tree t) {
        return source.substring(start(t), end(t));
    }

    void edit(Tree t, String replacement) {
        edits.add(new Edit(start(t), end(t), replacement));
    }

    boolean imported(String type) {
        return unit
            .getImports()
            .stream()
            .anyMatch(i -> !i.isStatic() && i.getQualifiedIdentifier().toString().equals(type));
    }

    boolean shadowed(String simpleName) {
        return paths
            .stream()
            .map(TreePath::getLeaf)
            .anyMatch(
                t ->
                    (t instanceof ClassTree c && c.getSimpleName().contentEquals(simpleName)) ||
                    (t instanceof VariableTree v && v.getName().contentEquals(simpleName))
            );
    }

    TreePath enclosing(TreePath path, Class<?> kind) {
        for (; path != null; path = path.getParentPath()) {
            if (kind.isInstance(path.getLeaf())) {
                return path;
            }
        }
        return null;
    }

    List<TreePath> inside(Tree tree) {
        return paths
            .stream()
            .filter(p -> start(p.getLeaf()) >= start(tree) && end(p.getLeaf()) <= end(tree))
            .toList();
    }

    List<VariableTree> variables(Tree method, String name) {
        return inside(method)
            .stream()
            .map(TreePath::getLeaf)
            .filter(VariableTree.class::isInstance)
            .map(VariableTree.class::cast)
            .filter(v -> v.getName().contentEquals(name))
            .toList();
    }

    boolean drawContext(MethodTree method) {
        return (
            imported("net.minecraft.client.gui.DrawContext") &&
            !shadowed("DrawContext") &&
            method.getParameters().stream().anyMatch(p -> p.getType().toString().equals("DrawContext"))
        );
    }

    boolean entityType(String type, ExpressionTree receiver, MethodTree method) {
        Map<String, String> owners = Map.of(
            "Entity",
            "net.minecraft.entity.Entity",
            "LivingEntity",
            "net.minecraft.entity.LivingEntity",
            "PlayerEntity",
            "net.minecraft.entity.player.PlayerEntity",
            "ClientPlayerEntity",
            "net.minecraft.client.network.ClientPlayerEntity",
            "ServerPlayerEntity",
            "net.minecraft.server.network.ServerPlayerEntity"
        );
        if (type == null || !owners.containsKey(type)) {
            return false;
        }
        List<String> importedTypes = unit
            .getImports()
            .stream()
            .filter(i -> !i.isStatic())
            .map(i -> i.getQualifiedIdentifier().toString())
            .filter(i -> i.endsWith("." + type))
            .toList();
        if (!importedTypes.isEmpty()) {
            return importedTypes.size() == 1 && importedTypes.getFirst().equals(owners.get(type));
        }
        // javac can report the type of MinecraftClient.player without importing ClientPlayerEntity.
        if (
            type.equals("ClientPlayerEntity") &&
            receiver instanceof MemberSelectTree player &&
            player.getIdentifier().contentEquals("player") &&
            imported("net.minecraft.client.MinecraftClient")
        ) {
            List<VariableTree> clients = variables(method, player.getExpression().toString());
            return clients.size() == 1 && clients.getFirst().getType().toString().equals("MinecraftClient");
        }
        return false;
    }

    static String preflight(MapKnown k, Path file, String source) throws IOException {
        ApiFix a = new ApiFix(file, source);
        for (TreePath p : a.paths) {
            if (
                !(p.getLeaf() instanceof MethodInvocationTree call) ||
                !(call.getMethodSelect() instanceof MemberSelectTree member)
            ) {
                continue;
            }
            TreePath enclosing = a.enclosing(p, MethodTree.class);
            if (enclosing == null) {
                continue;
            }
            MethodTree method = (MethodTree) enclosing.getLeaf();
            String name = member.getIdentifier().toString();
            boolean context =
                a.drawContext(method) &&
                method
                    .getParameters()
                    .stream()
                    .anyMatch(
                        v ->
                            v.getType().toString().equals("DrawContext") &&
                            v.getName().contentEquals(member.getExpression().toString())
                    );
            if (
                "yarn".equals(k.names) &&
                "1.21.5".equals(k.fromVersion) &&
                "1.21.6".equals(k.toVersion) &&
                context &&
                ((name.equals("drawText") && call.getArguments().size() == 6) ||
                    (name.equals("drawTextWithShadow") && call.getArguments().size() == 5)) &&
                k.sigTransitions
                    .getOrDefault("DrawContext." + name, Set.of())
                    .stream()
                    .anyMatch(transition -> {
                        String[] pair = transition.split(" -> ", 2);
                        return (
                            pair.length == 2 &&
                            pair[0].startsWith("(Lnet/minecraft/client/font/TextRenderer;") &&
                            pair[0].endsWith(")I") &&
                            pair[1].equals(pair[0].substring(0, pair[0].length() - 1) + "V")
                        );
                    })
            ) {
                String opaque = a.opaqueTextColor(call.getArguments().get(4));
                if (opaque != null) a.edit(call.getArguments().get(4), opaque);
            }
            if (
                "1.21.5".equals(k.toVersion) &&
                context &&
                a.imported("net.minecraft.client.gl.RenderPipelines") &&
                !a.shadowed("RenderPipelines") &&
                (name.equals("drawGuiTexture") || name.equals("drawTexture")) &&
                !call.getArguments().isEmpty() &&
                call.getArguments().getFirst().toString().equals("RenderPipelines.GUI_TEXTURED") &&
                k.sigTo
                    .getOrDefault("DrawContext." + name, Set.of())
                    .stream()
                    .anyMatch(s -> s.contains("Ljava/util/function/Function;"))
            ) {
                a.edit(call.getArguments().getFirst(), "net.minecraft.client.render.RenderLayer::getGuiTextured");
            }
            if (
                "1.21.5".equals(k.toVersion) &&
                context &&
                name.equals("getMatrices") &&
                call.getArguments().isEmpty() &&
                k.sigTo
                    .getOrDefault("DrawContext.getMatrices", Set.of())
                    .contains("getMatrices()Lnet/minecraft/client/util/math/MatrixStack;")
            ) {
                a.backportGuiMatrices(p, method, member);
            }
            if (
                member.toString().equals("InputUtil.isKeyPressed") &&
                a.imported("net.minecraft.client.util.InputUtil") &&
                !a.shadowed("InputUtil") &&
                k.sigTo
                    .getOrDefault("InputUtil.isKeyPressed", Set.of())
                    .contains("isKeyPressed(Lnet/minecraft/client/util/Window;I)Z") &&
                call.getArguments().size() == 2
            ) {
                String window = a.windowFor(call.getArguments().getFirst(), method);
                if (window != null) {
                    a.edit(call.getArguments().getFirst(), window);
                }
            }
        }
        return a.withEdits();
    }

    /** One lint finding: a call site the map knows, with its rewrite when it is unambiguous. */
    record LintHit(int line, String oldName, String detail, boolean auto) {}

    /** The lint result: findings for the report, and the source with unanimous renames applied. */
    static final class Lint {
        final List<LintHit> hits = new ArrayList<>();
        String fixed;
    }

    static int lineOf(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < source.length(); i++) {
            if (source.charAt(i) == 10) {
                line++;
            }
        }
        return line;
    }

    /** Call sites and field reads by their receiver: game code we can rewrite, mod code we leave alone. */
    static final class Scope {
        final Set<String> methods = new HashSet<>(), vars = new HashSet<>(), types = new HashSet<>();
        final Map<String, String> varTypes = new HashMap<>(), imports = new HashMap<>(), staticImports = new HashMap<>();
        final Set<String> ambiguousStatics = new HashSet<>();
    }

    static String simpleTypeName(String type) {
        String t = type.replaceAll("<.*", "").replace("[]", "").trim();
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }

    static Scope scopeOf(CompilationUnitTree unit, List<TreePath> paths) {
        Scope s = new Scope();
        for (TreePath p : paths) {
            if (p.getLeaf() instanceof MethodTree m) {
                s.methods.add(m.getName().toString());
            } else if (p.getLeaf() instanceof VariableTree v) {
                String name = v.getName().toString();
                s.vars.add(name);
                if (v.getType() != null) {
                    s.varTypes.put(name, simpleTypeName(v.getType().toString()));
                }
            } else if (p.getLeaf() instanceof ClassTree c) {
                s.types.add(c.getSimpleName().toString());
            }
        }
        for (ImportTree i : unit.getImports()) {
            String fqn = i.getQualifiedIdentifier().toString();
            int dot = fqn.lastIndexOf('.');
            if (dot <= 0) {
                continue;
            }
            if (i.isStatic()) {
                String name = fqn.substring(dot + 1);
                if (name.equals("*") || s.staticImports.containsKey(name)) {
                    s.ambiguousStatics.add(name);
                } else {
                    s.staticImports.put(name, fqn);
                }
            } else {
                s.imports.put(fqn.substring(dot + 1), fqn);
            }
        }
        return s;
        }

    static boolean gameImport(Scope scope, String simple) {
        String fqn = scope.imports.get(simple);
        return fqn != null && !scope.types.contains(simple) &&
            (fqn.startsWith("net.minecraft.") || fqn.startsWith("com.mojang."));
    }

    /** Whether a statically imported name unambiguously names game code. */
    static boolean gameStaticImport(Scope scope, String name) {
        String fqn = scope.staticImports.get(name);
        return fqn != null && !scope.ambiguousStatics.contains(name) && !scope.types.contains(name) &&
            (fqn.startsWith("net.minecraft.") || fqn.startsWith("com.mojang."));
    }

    /** The simple name of a type, or null when the tree is not a plain or qualified name. */
    static String typeName(Tree t) {
        if (t instanceof IdentifierTree id) {
            return id.getName().toString();
        }
        if (t instanceof MemberSelectTree m) {
            return m.getIdentifier().toString();
        }
        if (t instanceof ParameterizedTypeTree p) {
            return typeName(p.getType());
        }
        return null;
    }

    /** Whether the nearest enclosing class or interface directly extends a game type. */
    static boolean extendsGame(Scope scope, TreePath p) {
        for (TreePath q = p; q != null; q = q.getParentPath()) {
            if (!(q.getLeaf() instanceof ClassTree c)) {
                continue;
            }
            if (c.getExtendsClause() != null) {
                String sup = typeName(c.getExtendsClause());
                if (sup != null && gameImport(scope, sup)) {
                    return true;
                }
            }
            for (Tree io : c.getImplementsClause()) {
                String sup = typeName(io);
                if (sup != null && gameImport(scope, sup)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /** A bare identifier in a type, annotation or declaration position: a name, never a field read. */
    static boolean nonValuePosition(TreePath p) {
        TreePath parent = p.getParentPath();
        if (parent == null) {
            return true;
        }
        Tree t = parent.getLeaf();
        if (t instanceof VariableTree v) {
            return v.getType() == p.getLeaf();
        }
        if (t instanceof MethodTree m) {
            return m.getReturnType() == p.getLeaf();
        }
        if (t instanceof NewClassTree n) {
            return n.getIdentifier() == p.getLeaf();
        }
        if (t instanceof InstanceOfTree io) {
            return io.getType() == p.getLeaf();
        }
        if (
            t instanceof ClassTree ||
            t instanceof TypeParameterTree ||
            t instanceof ParameterizedTypeTree ||
            t instanceof ArrayTypeTree ||
            t instanceof WildcardTree ||
            t instanceof UnionTypeTree ||
            t instanceof IntersectionTypeTree ||
            t instanceof AnnotatedTypeTree ||
            t instanceof AnnotationTree
        ) {
            return true;
        }
        for (TreePath q = p; q != null; q = q.getParentPath()) {
            if (q.getLeaf() instanceof AnnotationTree) {
                return true;
            }
        }
        return false;
        }

    /** The simple type behind a call receiver, or null when the source cannot prove it. */
    static String receiverType(Scope scope, ExpressionTree receiver) {
        if (receiver instanceof NewClassTree n && n.getIdentifier() instanceof IdentifierTree id) {
            String name = id.getName().toString();
            return scope.imports.containsKey(name) ? simpleTypeName(scope.imports.get(name)) : name;
        }
        // Only the variable or type itself: in a.b.call() the receiver is b, whose type a does not tell.
        String root = receiver instanceof IdentifierTree id ? id.getName().toString() : null;
        if (root == null || root.equals("this") || root.equals("super")) {
            return null;
        }
        if (scope.varTypes.containsKey(root)) {
            return scope.varTypes.get(root);
        }
        if (scope.imports.containsKey(root)) {
            return simpleTypeName(scope.imports.get(root));
        }
        return null;
    }

    /** A member select that names a type or a package, not a member access. */
    static boolean qualifiedContext(TreePath p) {
        TreePath parent = p.getParentPath();
        if (parent == null) {
            return true;
        }
        if (parent.getLeaf() instanceof MemberSelectTree) {
            return true;
        }
        if (parent.getLeaf() instanceof ImportTree) {
            return true;
        }
        if (parent.getLeaf() instanceof VariableTree v && v.getType() == p.getLeaf()) {
            return true;
        }
        if (parent.getLeaf() instanceof MethodTree m && m.getReturnType() == p.getLeaf()) {
            return true;
        }
        if (parent.getLeaf() instanceof NewClassTree n && n.getIdentifier() == p.getLeaf()) {
            return true;
        }
        if (parent.getLeaf() instanceof ClassTree) {
            return true;
        }
        if (parent.getLeaf() instanceof InstanceOfTree io && io.getType() == p.getLeaf()) {
            return true;
        }
        for (TreePath q = p; q != null; q = q.getParentPath()) {
            if (q.getLeaf() instanceof ImportTree) {
                return true;
            }
        }
        return false;
    }

    static int callArity(String desc) {
        int depth = desc.indexOf('('), end = desc.indexOf(')');
        if (depth < 0 || end < 0) {
            return -1;
        }
        int count = 0, i = depth + 1;
        while (i < end) {
            char c = desc.charAt(i);
            if (c == '[') {
                i++;
            } else if (c == 'L') {
                int semi = desc.indexOf(';', i);
                if (semi < 0 || semi > end) {
                    return -1;
                }
                i = semi + 1;
                count++;
            } else {
                i++;
                count++;
            }
        }
        return count;
    }

    static String stepsFor(MapKnown k, String kind, String name, Set<String> whats) {
        String key = k.names + "\0" + k.fromVersion + "\0" + k.toVersion + "\0" + kind + "\0" + name + "\0" + String.join(",", new TreeSet<>(whats));
        return stepsForCache.computeIfAbsent(key, x -> {
            List<String> steps = new ArrayList<>();
            List<Map<String, String>> bucket = k.historyByName.get(kind + "\0" + name);
            if (bucket != null) {
                for (Map<String, String> e : bucket) {
                    if (whats.contains(e.get("what"))) {
                        steps.add(e.get("step"));
                    }
                }
            } else if (k.historyByName.isEmpty()) {
                for (Map<String, String> e : k.history) {
                    if (kind.equals(e.get("kind")) && name.equals(e.get("name")) && whats.contains(e.get("what"))) {
                        steps.add(e.get("step"));
                    }
                }
            }
            return String.join(", ", new LinkedHashSet<>(steps));
        });
    }

    static final Map<String, String> stepsForCache = new HashMap<>();

    static String candidatesDetail(Set<String[]> candidates) {
        Set<String> names = new TreeSet<>();
        for (String[] c : candidates) {
            names.add(c[1]);
        }
        return String.join("|", names);
    }

    /**
     * Map-driven lint for any namespace: parse only, no build. Every method call and dotted
     * field read whose name the map renamed, moved or removed is reported with the update that
     * A rename is rewritten only when it is unanimous (one new name everywhere),
     * the file does not declare the name itself, and the receiver provably names a game type
     * (or is a game static import); unqualified calls in a game subclass and everything else
     * are reported for a human.
     */
    static Lint lintKnown(MapKnown k, Path file, String source) throws IOException {
        return lintParsed(k, new ApiFix(file, source), source);
    }

    static Lint lintParsed(MapKnown k, ApiFix a, String source) throws IOException {
        Lint out = new Lint();
        out.fixed = source;
        List<String> lines = Arrays.asList(source.split("\\R", -1));
        Scope scope = scopeOf(a.unit, a.paths);
        for (TreePath p : a.paths) {
            if (p.getLeaf() instanceof MethodInvocationTree call && call.getMethodSelect() instanceof MemberSelectTree member) {
                String name = member.getIdentifier().toString();
                int line = lineOf(source, a.start(member));
                if (scope.methods.contains(name)) {
                    if (k.methodTo.containsKey(name) || k.methodGone.contains(name) || k.sigNames.contains(name)) {
                        out.hits.add(new LintHit(line, name, name + " declared here; skipped [self-declared]", false));
                    }
                    continue;
                }
                Set<String[]> candidates = k.methodTo.get(name);
                if (candidates == null || candidates.isEmpty()) {
                    if (k.methodGone.contains(name)) {
                        out.hits.add(
                            new LintHit(line, name, name + " removed (" + stepsFor(k, "method", name, Set.of("removed")) + ") [hand-fix]", false)
                        );
                    } else if (k.sigNames.contains(name)) {
                        boolean any = false;
                        for (Map.Entry<String, Set<String>> e : k.sigTo.entrySet()) {
                            if (!e.getKey().endsWith("." + name)) {
                                continue;
                            }
                            for (String desc : e.getValue()) {
                                if (callArity(desc) == call.getArguments().size()) {
                                    any = true;
                                }
                            }
                        }
                        if (!any) {
                            out.hits.add(
                                new LintHit(line, name, name + " signature changed (" + stepsFor(k, "method", name, Set.of("signature")) + ") [review args]", false)
                            );
                        }
                    }
                    continue;
                }
                String steps = stepsFor(k, "method", name, Set.of("renamed", "moved"));
                String type = receiverType(scope, member.getExpression());
                boolean game = type != null && gameImport(scope, type);
                String to = game ? MapKnown.forType(candidates, type) : null;
                boolean applied = false;
                if (to != null) {
                    String next = replaceCall(out.fixed, line, name, to);
                    applied = !next.equals(out.fixed);
                    out.fixed = next;
                }
                String where = member.getExpression() + "." + name;
                if (applied) {
                    out.hits.add(new LintHit(line, name, where + " -> " + to + " (" + steps + ") [auto]", true));
                } else {
                    out.hits.add(
                        new LintHit(line, name, where + " -> " + candidatesDetail(candidates) + " (" + steps + ") [review]", false)
                    );
                }
            } else if (p.getLeaf() instanceof MemberSelectTree member && !qualifiedContext(p)) {
                String name = member.getIdentifier().toString();
                int line = lineOf(source, a.start(member));
                if (scope.vars.contains(name)) {
                    if (k.fieldTo.containsKey(name) || k.fieldGone.contains(name)) {
                        out.hits.add(new LintHit(line, name, name + " declared here; skipped [self-declared]", false));
                    }
                    continue;
                }
                String lineText = line >= 1 && line <= lines.size() ? lines.get(line - 1) : "";
                Set<String[]> candidates = k.fieldTo.get(name);
                if (candidates == null || candidates.isEmpty()) {
                    if (k.fieldGone.contains(name)) {
                        out.hits.add(
                            new LintHit(line, name, name + " removed (" + stepsFor(k, "field", name, Set.of("removed")) + ") [hand-fix]", false)
                        );
                    }
                    continue;
                }
                if (lineText.matches(".*\\b" + Pattern.quote(name) + "\\s*\\(.*")) {
                    out.hits.add(new LintHit(line, name, name + " -> " + candidatesDetail(candidates) + " [review: call-shaped]", false));
                    continue;
                }
                String steps = stepsFor(k, "field", name, Set.of("renamed", "moved"));
                String type = receiverType(scope, member.getExpression());
                boolean game = type != null && gameImport(scope, type);
                String to = game ? MapKnown.forType(candidates, type) : null;
                boolean applied = false;
                if (to != null) {
                    String next = replaceField(out.fixed, line, name, to);
                    applied = !next.equals(out.fixed);
                    out.fixed = next;
                }
                String where = member.getExpression() + "." + name;
                if (applied) {
                    out.hits.add(new LintHit(line, name, where + " -> " + to + " (" + steps + ") [auto]", true));
                } else {
                    out.hits.add(new LintHit(line, name, where + " -> " + candidatesDetail(candidates) + " (" + steps + ") [review]", false));
                }
            } else if (
                p.getLeaf() instanceof MethodInvocationTree call &&
                call.getMethodSelect() instanceof IdentifierTree id &&
                !(call.getMethodSelect() instanceof MemberSelectTree)
            ) {
                // An unqualified call: provable only through a static import or an inherited
                // game member. Anything else is the mod's own code or broken code; stay silent.
                String name = id.getName().toString();
                if (scope.methods.contains(name) || !k.methodTo.containsKey(name)) {
                    continue;
                }
                int line = lineOf(source, a.start(call));
                String steps = stepsFor(k, "method", name, Set.of("renamed", "moved"));
                if (gameStaticImport(scope, name)) {
                    String to = MapKnown.unanimous(k.methodTo.get(name));
                    if (to != null) {
                        String next = replaceCall(out.fixed, line, name, to);
                        if (!next.equals(out.fixed)) {
                            out.fixed = next;
                            out.hits.add(new LintHit(line, name, name + " -> " + to + " (" + steps + ") [auto: static import]", true));
                            continue;
                        }
                    }
                }
                if (!extendsGame(scope, p)) {
                    continue;
                }
                out.hits.add(
                    new LintHit(
                        line,
                        name,
                        name + " -> " + candidatesDetail(k.methodTo.get(name)) + " (" + steps + ") [review: unqualified call in game subclass]",
                        false
                    )
                );
            } else if (p.getLeaf() instanceof IdentifierTree id && !(p.getParentPath() instanceof MemberSelectTree) && !nonValuePosition(p)) {
                // A bare name: a local (skipped silently), an inherited game field (reported),
                // a static import (auto when unanimous), or nothing we can prove (skipped).
                String name = id.getName().toString();
                if (scope.vars.contains(name) || scope.methods.contains(name) || !k.fieldTo.containsKey(name)) {
                    continue;
                }
                int line = lineOf(source, a.start(id));
                String steps = stepsFor(k, "field", name, Set.of("renamed", "moved"));
                if (gameStaticImport(scope, name)) {
                    String to = MapKnown.unanimous(k.fieldTo.get(name));
                    if (to != null) {
                        String next = replaceField(out.fixed, line, name, to);
                        if (!next.equals(out.fixed)) {
                            out.fixed = next;
                            out.hits.add(new LintHit(line, name, name + " -> " + to + " (" + steps + ") [auto: static import]", true));
                            continue;
                        }
                    }
                }
                if (!extendsGame(scope, p)) {
                    continue;
                }
                out.hits.add(
                    new LintHit(
                        line,
                        name,
                        name + " -> " + candidatesDetail(k.fieldTo.get(name)) + " (" + steps + ") [review: unqualified field in game subclass]",
                        false
                    )
                );
            }
        }
        return out;
    }

    static String replaceCall(String source, int line, String from, String to) {
        return replaceOnLine(source, line, "\\b" + Pattern.quote(from) + "(?=\\s*\\()", Matcher.quoteReplacement(to));
    }

    static String replaceField(String source, int line, String from, String to) {
        return replaceOnLine(source, line, "\\b" + Pattern.quote(from) + "\\b(?![\\w$])", Matcher.quoteReplacement(to));
    }

    static String replaceOnLine(String source, int line, String pattern, String replacement) {
        String nl = source.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = source.split("\r\n|\n", -1);
        if (line < 1 || line > lines.length) {
            return source;
        }
        lines[line - 1] = Pattern.compile(pattern).matcher(lines[line - 1]).replaceAll(replacement);
        return String.join(nl, lines);
    }

    String opaqueTextColor(ExpressionTree color) {
        if (color instanceof LiteralTree literal && literal.getValue() instanceof Integer value) {
            return (value & 0xFC000000) == 0 ? String.format("0x%08X", value | 0xFF000000) : null;
        }
        boolean stable =
            color instanceof IdentifierTree ||
            (color instanceof MemberSelectTree member && member.getExpression() instanceof IdentifierTree);
        if (!stable) return null;
        String value = text(color);
        return "((" + value + " & 0xFC000000) == 0 ? (" + value + " | 0xFF000000) : " + value + ")";
    }

    static String apply(MapKnown k, Path file, String source, List<VersionMap.Err> errors) throws IOException {
        if (!"yarn".equals(k.names)) {
            return source;
        }
        ApiFix a = new ApiFix(file, source);
        String[] lines = source.split("\r?\n", -1);
        for (VersionMap.Err e : errors) {
            // A preceding rule/import can invalidate a supplied log. Rebuild before using those positions.
            if (
                e.caret() < 0 ||
                e.line() < 1 ||
                e.line() > lines.length ||
                !lines[e.line() - 1].strip().equals(e.source().strip())
            ) {
                continue;
            }
            long at = a.unit.getLineMap().getPosition(e.line(), 1) + e.caret();
            for (TreePath p : a.paths) {
                Tree tree = p.getLeaf();
                if (a.start(tree) > at || a.end(tree) <= at) {
                    continue;
                }
                TreePath mp = a.enclosing(p, MethodTree.class);
                if (mp == null) {
                    continue;
                }
                MethodTree method = (MethodTree) mp.getLeaf();
                if (
                    tree instanceof MemberSelectTree field &&
                    field.toString().equals("MinecraftClient.IS_SYSTEM_MAC") &&
                    a.imported("net.minecraft.client.MinecraftClient") &&
                    k.fieldGone.contains("IS_SYSTEM_MAC") &&
                    "1.21.9".equals(k.toVersion)
                ) {
                    a.edit(field, "net.minecraft.client.input.SystemKeycodes.IS_MAC_OS");
                }
                if (
                    tree instanceof MethodInvocationTree call &&
                    call.getMethodSelect() instanceof MemberSelectTree member
                ) {
                    String name = member.getIdentifier().toString();
                    String type = MapKnown.locationType(e.location());
                    if (
                        "1.21.5".equals(k.toVersion) &&
                        e
                            .message()
                            .contains("RenderPipeline cannot be converted to Function<Identifier,RenderLayer>") &&
                        a.drawContext(method) &&
                        a.imported("net.minecraft.client.gl.RenderPipelines") &&
                        !a.shadowed("RenderPipelines") &&
                        (name.equals("drawGuiTexture") || name.equals("drawTexture")) &&
                        k.sigTo
                            .getOrDefault("DrawContext." + name, Set.of())
                            .stream()
                            .anyMatch(s -> s.contains("Ljava/util/function/Function;")) &&
                        !call.getArguments().isEmpty() &&
                        call.getArguments().getFirst().toString().equals("RenderPipelines.GUI_TEXTURED") &&
                        method
                            .getParameters()
                            .stream()
                            .anyMatch(
                                v ->
                                    v.getType().toString().equals("DrawContext") &&
                                    v.getName().contentEquals(member.getExpression().toString())
                            )
                    ) {
                        a.edit(
                            call.getArguments().getFirst(),
                            "net.minecraft.client.render.RenderLayer::getGuiTextured"
                        );
                    }
                    if (
                        "1.21.5".equals(k.toVersion) &&
                        e.message().contains("MatrixStack cannot be converted to Matrix3x2fStack") &&
                        name.equals("getMatrices") &&
                        a.drawContext(method) &&
                        k.sigTo
                            .getOrDefault("DrawContext.getMatrices", Set.of())
                            .contains("getMatrices()Lnet/minecraft/client/util/math/MatrixStack;")
                    ) {
                        a.backportGuiMatrices(p, method, member);
                    }
                    if (
                        e.message().equals("cannot find symbol") &&
                        e.symbol() != null &&
                        e.symbol().startsWith("method " + name + "(") &&
                        call.getArguments().isEmpty() &&
                        a.entityType(type, member.getExpression(), method)
                    ) {
                        String to = name.equals("getEntityWorld") && "1.21.6".equals(k.toVersion)
                            ? "getWorld"
                            : name.equals("getWorld") && "1.21.9".equals(k.toVersion) ? "getEntityWorld" : null;
                        if (to != null && k.methodGone.contains(name)) {
                            a.edits.add(new Edit(a.end(member) - name.length(), a.end(member), to));
                        }
                    }
                    if (
                        member.toString().equals("InputUtil.isKeyPressed") &&
                        a.imported("net.minecraft.client.util.InputUtil") &&
                        !a.shadowed("InputUtil") &&
                        k.sigTo
                            .getOrDefault("InputUtil.isKeyPressed", Set.of())
                            .contains("isKeyPressed(Lnet/minecraft/client/util/Window;I)Z") &&
                        e.message().contains("long cannot be converted to Window") &&
                        call.getArguments().size() == 2
                    ) {
                        ExpressionTree arg = call.getArguments().getFirst();
                        String window = a.windowFor(arg, method);
                        if (window != null) {
                            a.edit(arg, window);
                        }
                    }
                    if (
                        member.toString().equals("RenderSystem.setShaderColor") &&
                        e.message().equals("cannot find symbol") &&
                        e.symbol() != null &&
                        e.symbol().startsWith("method setShaderColor(") &&
                        a.imported("com.mojang.blaze3d.systems.RenderSystem") &&
                        a.drawContext(method) &&
                        k.sigTo
                            .getOrDefault("DrawContext.getMatrices", Set.of())
                            .contains("getMatrices()Lorg/joml/Matrix3x2fStack;") &&
                        call.getArguments().size() == 4 &&
                        call
                            .getArguments()
                            .stream()
                            .allMatch(
                                x ->
                                    x instanceof LiteralTree l &&
                                    l.getValue() instanceof Number n &&
                                    n.doubleValue() == 1.0
                            ) &&
                        p.getParentPath().getLeaf() instanceof ExpressionStatementTree statement
                    ) {
                        a.edit(statement, "");
                    }
                    if (
                        name.equals("getMatrices") &&
                        e.message().contains("Matrix3x2fStack cannot be converted to MatrixStack") &&
                        a.drawContext(method)
                    ) {
                        a.guiMatrices(p, method, member);
                    }
                }
            }
        }
        return a.withEdits();
    }

    String withEdits() {
        // Deduplicate diagnostics/overlapping selections, then edit from right to left.
        List<Edit> unique = new ArrayList<>(new LinkedHashSet<>(edits));
        unique.sort(Comparator.comparingInt(Edit::start).reversed());
        int boundary = source.length();
        String result = source;
        for (Edit e : unique) {
            if (e.start() < 0 || e.end() > boundary) {
                continue;
            }
            result = result.substring(0, e.start()) + e.text() + result.substring(e.end());
            boundary = e.start();
        }
        return result;
    }

    void backportGuiMatrices(TreePath callPath, MethodTree method, MemberSelectTree getMatrices) {
        Tree parent = callPath.getParentPath().getLeaf();
        String name = parent instanceof AssignmentTree assign ? assign.getVariable().toString() : "";
        List<VariableTree> vars = variables(method, name);
        if (
            vars.size() != 1 ||
            !vars.getFirst().getType().toString().equals("Matrix3x2fStack") ||
            !imported("org.joml.Matrix3x2fStack")
        ) {
            return;
        }
        String context = getMatrices.getExpression().toString();
        if (
            method
                .getParameters()
                .stream()
                .noneMatch(v -> v.getName().contentEquals(context) && v.getType().toString().equals("DrawContext"))
        ) {
            return;
        }
        List<Edit> planned = new ArrayList<>();
        planned.add(
            new Edit(
                start(vars.getFirst().getType()),
                end(vars.getFirst().getType()),
                "net.minecraft.client.util.math.MatrixStack"
            )
        );
        for (TreePath p : inside(method)) {
            if (!(p.getLeaf() instanceof IdentifierTree id) || !id.getName().contentEquals(name)) {
                continue;
            }
            Tree up = p.getParentPath().getLeaf();
            if (
                up instanceof AssignmentTree assign &&
                assign.getVariable() == id &&
                assign.getExpression() == callPath.getLeaf()
            ) {
                continue;
            }
            if (up instanceof VariableTree variable && variable == vars.getFirst()) {
                continue;
            }
            if (
                !(up instanceof MemberSelectTree member) ||
                member.getExpression() != id ||
                !(p.getParentPath().getParentPath().getLeaf() instanceof MethodInvocationTree call)
            ) {
                return;
            }
            String operation = member.getIdentifier().toString();
            List<? extends ExpressionTree> args = call.getArguments();
            String replacement;
            if ((operation.equals("pushMatrix") || operation.equals("popMatrix")) && args.isEmpty()) {
                replacement = name + "." + (operation.equals("pushMatrix") ? "push" : "pop") + "()";
            } else if ((operation.equals("translate") || operation.equals("scale")) && args.size() == 2) {
                replacement =
                    name +
                    "." +
                    operation +
                    "(" +
                    text(args.get(0)) +
                    ", " +
                    text(args.get(1)) +
                    (operation.equals("translate") ? ", 0" : ", 1") +
                    ")";
            } else {
                return;
            }
            planned.add(new Edit(start(call), end(call), replacement));
        }
        edits.addAll(planned);
    }

    String windowFor(ExpressionTree argument, MethodTree method) {
        ExpressionTree value = argument;
        if (argument instanceof IdentifierTree id) {
            List<VariableTree> vars = variables(method, id.getName().toString());
            if (vars.size() != 1 || vars.getFirst().getInitializer() == null) {
                return null;
            }
            value = vars.getFirst().getInitializer();
            for (TreePath p : inside(method)) {
                Tree t = p.getLeaf();
                if (
                    (t instanceof AssignmentTree assign && assign.getVariable().toString().equals(id.toString())) ||
                    (t instanceof CompoundAssignmentTree compound &&
                        compound.getVariable().toString().equals(id.toString())) ||
                    (t instanceof UnaryTree unary &&
                        unary.getExpression().toString().equals(id.toString()) &&
                        Set.of(
                            Tree.Kind.PREFIX_INCREMENT,
                            Tree.Kind.PREFIX_DECREMENT,
                            Tree.Kind.POSTFIX_INCREMENT,
                            Tree.Kind.POSTFIX_DECREMENT
                        ).contains(t.getKind()))
                ) {
                    return null;
                }
            }
        }
        if (
            value instanceof MethodInvocationTree call &&
            call.getArguments().isEmpty() &&
            call.getMethodSelect().toString().equals("MinecraftClient.getInstance().getWindow().getHandle") &&
            imported("net.minecraft.client.MinecraftClient") &&
            !shadowed("MinecraftClient")
        ) {
            return "MinecraftClient.getInstance().getWindow()";
        }
        return null;
    }

    void guiMatrices(TreePath callPath, MethodTree method, MemberSelectTree getMatrices) {
        // Only a local GUI stack with known uses. Passing it elsewhere or mixing in 3D operations needs a guided port.
        Tree parent = callPath.getParentPath().getLeaf();
        String name = parent instanceof AssignmentTree assign
            ? assign.getVariable().toString()
            : parent instanceof VariableTree v ? v.getName().toString() : "";
        List<VariableTree> vars = variables(method, name);
        if (
            vars.size() != 1 ||
            !vars.getFirst().getType().toString().equals("MatrixStack") ||
            !imported("net.minecraft.client.util.math.MatrixStack")
        ) {
            return;
        }
        String context = getMatrices.getExpression().toString();
        if (
            method
                .getParameters()
                .stream()
                .noneMatch(v -> v.getName().contentEquals(context) && v.getType().toString().equals("DrawContext"))
        ) {
            return;
        }
        List<Edit> planned = new ArrayList<>();
        VariableTree variable = vars.getFirst();
        if (method.getParameters().contains(variable)) {
            return;
        }
        if (variable.getInitializer() != null && variable.getInitializer() != callPath.getLeaf()) {
            return;
        }
        planned.add(new Edit(start(variable.getType()), end(variable.getType()), "org.joml.Matrix3x2fStack"));
        for (TreePath p : inside(method)) {
            if (!(p.getLeaf() instanceof IdentifierTree id) || !id.getName().contentEquals(name)) {
                continue;
            }
            Tree up = p.getParentPath().getLeaf();
            if (
                up instanceof AssignmentTree assign &&
                assign.getVariable() == id &&
                assign.getExpression() == callPath.getLeaf()
            ) {
                continue;
            }
            if (
                !(up instanceof MemberSelectTree member) ||
                member.getExpression() != id ||
                !(p.getParentPath().getParentPath().getLeaf() instanceof MethodInvocationTree call)
            ) {
                return;
            }
            String operation = member.getIdentifier().toString();
            List<? extends ExpressionTree> args = call.getArguments();
            String replacement;
            if ((operation.equals("push") || operation.equals("pop")) && args.isEmpty()) {
                replacement = name + "." + operation + "Matrix()";
            } else if (
                (operation.equals("translate") || operation.equals("scale")) &&
                args.size() == 3 &&
                (args.get(2) instanceof IdentifierTree || args.get(2) instanceof LiteralTree)
            ) {
                // GUI draw order replaces Z depth. Keep X/Y unchanged and never discard a call with side effects.
                replacement = name + "." + operation + "(" + text(args.get(0)) + ", " + text(args.get(1)) + ")";
            } else {
                return;
            }
            planned.add(new Edit(start(call), end(call), replacement));
        }
        edits.addAll(planned);
    }
}
