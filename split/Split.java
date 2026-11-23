import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import javax.lang.model.element.*;
import javax.tools.*;

/**
 * Split - move members of a big class to other top-level classes, by a plan, with every reference re-pointed from
 * javac's own symbols. Pure moves: no statement changes other than the qualifier in front of a moved name.
 *
 *   java Split.java PLAN [--survey OUT.json] [--dry]
 *
 * Plan lines (# comments):
 *   release 25
 *   classpath a.jar b.jar
 *   source GLOB ...                      every file that is compiled together (hosts, their users, tests)
 *   host Name ctx                        a class to split; ctx is the field name its parts reach it by
 *   part Class field path/Class.java | One sentence saying what lives here.
 *     member() field NestedType ...      (indented lines: the members that move there)
 *   promote NestedType path/Nested.java  a nested class that becomes a top-level class in its own file
 * A member nothing names stays in the host.
 */
public class Split {

    static final class Home {
        String cls, field, file, doc, was;
        boolean promoted, core, needsCtx;
        Host host;
        final List<int[]> ranges = new ArrayList<>();
        ClassTree promotedTree;

        public String toString() {
            return cls;
        }
    }

    static final class Host {
        String name, ctx;
        TypeElement type;
        CompilationUnitTree cu;
        ClassTree tree;
        Home core;
        final Map<String, Home> rules = new LinkedHashMap<>(); // member key -> home
        final List<Home> homes = new ArrayList<>();
        final Map<Tree, Home> memberHome = new IdentityHashMap<>();
        final Map<Element, Home> homeOf = new HashMap<>();
        final Set<String> seen = new HashSet<>();
        final Map<String, String> bridges = new LinkedHashMap<>(); // bridge signature -> its text
    }

    record Edit(int start, int end, String text, int order) {}

    static final List<Host> hosts = new ArrayList<>();
    static final Map<Element, Host> hostByType = new HashMap<>();
    static final Map<Tree, Host> hostByTree = new IdentityHashMap<>();
    static final Map<CompilationUnitTree, List<Edit>> edits = new IdentityHashMap<>();
    static final Map<CompilationUnitTree, String> text = new IdentityHashMap<>();
    static final Map<CompilationUnitTree, Path> pathOf = new IdentityHashMap<>();
    static final Map<CompilationUnitTree, List<Object[]>> typeUses = new IdentityHashMap<>(); // {pos, TypeElement}
    static final Set<Element> needsOuter = new HashSet<>();
    static final Set<Element> makeStatic = new HashSet<>();
    static final Set<Tree> skipType = Collections.newSetFromMap(new IdentityHashMap<>());
    static final Set<Tree> stripped = Collections.newSetFromMap(new IdentityHashMap<>());
    static Trees trees;
    static javax.lang.model.util.Types types;
    static SourcePositions pos;
    static int order;
    static final Set<String> made = new HashSet<>();
    static final List<String> problems = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path planFile = Path.of(args[0]);
        String survey = null;
        boolean dry = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--survey")) {
                survey = args[++i];
            } else if (args[i].equals("--dry")) {
                dry = true;
            }
        }
        String release = "25";
        List<String> classpath = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        List<String> sourcepath = new ArrayList<>();
        Host host = null;
        Home current = null;
        for (String raw : Files.readAllLines(planFile)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] w = line.split("\\s+");
            boolean indented = raw.startsWith(" ") || raw.startsWith("\t");
            if (indented && current != null) {
                for (String key : w) {
                    Home was = host.rules.put(key, current);
                    if (was != null && was != current) {
                        problems.add("plan names " + key + " twice: " + was.cls + " and " + current.cls);
                    }
                }
                continue;
            }
            switch (w[0]) {
                case "release" -> release = w[1];
                case "classpath" -> classpath.addAll(List.of(w).subList(1, w.length));
                case "source" -> {
                    for (String glob : List.of(w).subList(1, w.length)) {
                        sources.addAll(glob(glob));
                    }
                }
                case "exclude" -> {
                    for (String glob : List.of(w).subList(1, w.length)) {
                        sources.removeAll(glob(glob));
                    }
                }
                case "sourcepath" -> sourcepath.addAll(List.of(w).subList(1, w.length));
                case "host" -> {
                    host = new Host();
                    host.name = w[1];
                    host.ctx = w[2];
                    host.core = new Home();
                    host.core.cls = host.name;
                    host.core.core = true;
                    host.core.host = host;
                    hosts.add(host);
                    current = null;
                }
                case "part" -> {
                    current = new Home();
                    current.cls = w[1];
                    current.field = w[2];
                    current.file = w[3];
                    current.host = host;
                    int bar = line.indexOf('|');
                    current.doc = bar < 0 ? null : line.substring(bar + 1).strip();
                    host.homes.add(current);
                }
                case "promote" -> {
                    Home h = new Home();
                    h.was = w[1];
                    h.cls = w.length > 4 && w[3].equals("as") ? w[4] : w[1];
                    h.file = w[2];
                    h.promoted = true;
                    h.host = host;
                    host.homes.add(h);
                    host.rules.put(w[1], h);
                    current = null;
                }
                default -> throw new IllegalArgumentException("plan: " + line);
            }
        }
        sources = new ArrayList<>(new LinkedHashSet<>(sources));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        List<String> options = new ArrayList<>(List.of("--release", release, "-proc:none", "-encoding", "UTF-8", "-Xmaxerrs", "50"));
        if (!classpath.isEmpty()) {
            options.add("-cp");
            options.add(String.join(File.pathSeparator, classpath));
        }
        if (!sourcepath.isEmpty()) {
            options.add("-sourcepath");
            options.add(String.join(File.pathSeparator, sourcepath));
        }
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        // Sources are read with their line ends normalised, so positions are the same on every checkout.
        List<JavaFileObject> units = new ArrayList<>();
        Map<String, Boolean> crlf = new HashMap<>();
        for (Path p : sources) {
            String raw = Files.readString(p);
            crlf.put(p.toAbsolutePath().normalize().toString(), raw.contains("\r\n"));
            String content = raw.replace("\r\n", "\n");
            units.add(new SimpleJavaFileObject(p.toAbsolutePath().normalize().toUri(), JavaFileObject.Kind.SOURCE) {
                public CharSequence getCharContent(boolean ignore) {
                    return content;
                }
            });
        }
        JavacTask task = (JavacTask) compiler.getTask(null, fm, diags, options, null, units);
        List<CompilationUnitTree> cus = new ArrayList<>();
        for (CompilationUnitTree cu : task.parse()) {
            cus.add(cu);
        }
        task.analyze();
        boolean failed = false;
        for (var d : diags.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                System.err.println(d);
                failed = true;
            }
        }
        if (failed) {
            System.exit(2);
        }
        trees = Trees.instance(task);
        types = task.getTypes();
        pos = trees.getSourcePositions();
        for (CompilationUnitTree cu : cus) {
            text.put(cu, cu.getSourceFile().getCharContent(true).toString());
            pathOf.put(cu, Path.of(cu.getSourceFile().toUri()));
            edits.put(cu, new ArrayList<>());
            typeUses.put(cu, new ArrayList<>());
        }
        for (Host h : hosts) {
            h.type = task.getElements().getTypeElement(h.name);
            if (h.type == null) {
                throw new IllegalStateException("no class " + h.name);
            }
            h.tree = trees.getTree(h.type);
            h.cu = trees.getPath(h.type).getCompilationUnit();
            hostByType.put(h.type, h);
            hostByTree.put(h.tree, h);
            members(h);
        }
        outer();
        if (survey != null) {
            survey(Path.of(survey));
        }
        for (CompilationUnitTree cu : cus) {
            new Rewriter(cu).scan(cu, null);
        }
        for (Host h : hosts) {
            ctors(h);
        }
        if (!problems.isEmpty()) {
            problems.forEach(System.err::println);
            System.exit(2);
        }
        if (dry) {
            return;
        }
        for (CompilationUnitTree cu : cus) {
            emit(cu, crlf.get(pathOf.get(cu).toString()));
        }
    }

    static List<Path> glob(String glob) throws IOException {
        if (!glob.contains("*")) {
            return List.of(Path.of(glob));
        }
        int slash = glob.lastIndexOf('/');
        Path dir = Path.of(slash < 0 ? "." : glob.substring(0, slash));
        String pattern = glob.substring(slash + 1);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> found = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir, pattern)) {
            stream.forEach(found::add);
        }
        Collections.sort(found);
        return found;
    }

    static String key(Tree member, Element e) {
        if (member instanceof MethodTree m) {
            return m.getName() + "()";
        }
        if (member instanceof VariableTree v) {
            return v.getName().toString();
        }
        if (member instanceof ClassTree c) {
            return c.getSimpleName().toString();
        }
        return "<init-block>";
    }

    static int lineEnd(String s, int at) {
        int n = s.indexOf('\n', at);
        return n < 0 ? s.length() : n + 1;
    }

    /** Each member of the host with its home and the text that travels with it: from the end of the member before it. */
    static void members(Host h) {
        String s = text.get(h.cu);
        TreePath classPath = trees.getPath(h.type);
        int open = s.indexOf('{', (int) pos.getStartPosition(h.cu, h.tree));
        // The class's own opening brace: the first one after its name.
        open = s.indexOf('{', s.indexOf(h.name, (int) pos.getStartPosition(h.cu, h.tree)));
        int cursor = lineEnd(s, open);
        Home last = null;
        int lastEnd = -1;
        for (Tree member : h.tree.getMembers()) {
            int start = (int) pos.getStartPosition(h.cu, member);
            int end = (int) pos.getEndPosition(h.cu, member);
            if (end < 0 || start < 0) {
                h.memberHome.put(member, h.core);
                continue;
            }
            Element e = trees.getElement(new TreePath(classPath, member));
            if (member instanceof MethodTree m && m.getName().contentEquals("<init>") && !explicitCtor(s, start, h.name)) {
                h.memberHome.put(member, h.core);
                if (e != null) {
                    h.homeOf.put(e, h.core);
                }
                continue;
            }
            String key = key(member, e);
            h.seen.add(key);
            Home home = h.rules.getOrDefault(key, h.core);
            if (member instanceof MethodTree m && m.getName().contentEquals("<init>")) {
                home = h.core;
            }
            if (home.promoted && !(member instanceof ClassTree)) {
                problems.add("promote " + key + ": not a nested class");
            }
            if (start < lastEnd) {
                // int a, b; is two members in one declaration: they travel together.
                if (home != last) {
                    problems.add(key + " is declared with another member that goes elsewhere");
                }
                h.memberHome.put(member, home);
                if (e != null) {
                    h.homeOf.put(e, home);
                }
                continue;
            }
            int stop = lineEnd(s, end);
            // A trailing comment after the member on its line stays with it; so does what is written above it.
            home.ranges.add(new int[] { cursor, stop });
            if (home.promoted) {
                home.promotedTree = (ClassTree) member;
            }
            cursor = stop;
            lastEnd = end;
            last = home;
            h.memberHome.put(member, home);
            if (e != null) {
                h.homeOf.put(e, home);
            }
            if (!home.core && !home.promoted) {
                boolean isStatic = e != null && e.getModifiers().contains(Modifier.STATIC) || member instanceof ClassTree c && c.getKind() != Tree.Kind.CLASS;
                if (!isStatic && !(member instanceof ClassTree)) {
                    home.needsCtx = true;
                }
            }
        }
        for (String key : h.rules.keySet()) {
            if (!h.seen.contains(key)) {
                System.err.println("note: " + h.name + " has no member " + key);
            }
        }
    }

    static boolean explicitCtor(String s, int start, String name) {
        int paren = s.indexOf('(', start);
        if (paren < 0) {
            return false;
        }
        String head = s.substring(start, paren);
        return Pattern.compile("(^|\\s)" + Pattern.quote(name) + "\\s*$").matcher(head).find() && !head.contains("class ") && !head.contains("record ");
    }

    static boolean inner(Element e) {
        return e != null && e.getKind() == ElementKind.CLASS && !e.getModifiers().contains(Modifier.STATIC) && hostByType.containsKey(e.getEnclosingElement());
    }

    /** Which inner classes of a host use the host's instance: those keep a way to reach it when they move. */
    static void outer() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Host h : hosts) {
                TreePath classPath = trees.getPath(h.type);
                for (Tree member : h.tree.getMembers()) {
                    if (!(member instanceof ClassTree c) || c.getKind() != Tree.Kind.CLASS) {
                        continue;
                    }
                    Element ce = trees.getElement(new TreePath(classPath, member));
                    if (!inner(ce) || needsOuter.contains(ce)) {
                        continue;
                    }
                    boolean[] uses = { false };
                    Element sup = c.getExtendsClause() == null ? null : trees.getElement(new TreePath(new TreePath(classPath, member), c.getExtendsClause()));
                    if (sup != null && needsOuter.contains(sup)) {
                        uses[0] = true;
                    }
                    new TreePathScanner<Void, Void>() {
                        public Void visitIdentifier(IdentifierTree id, Void v) {
                            Element e = trees.getElement(getCurrentPath());
                            if (e != null) {
                                if (id.getName().contentEquals("this")) {
                                    if (e.getEnclosingElement() == h.type) {
                                        uses[0] = true;
                                    }
                                } else if (e.getEnclosingElement() == h.type && !e.getModifiers().contains(Modifier.STATIC)
                                    && (e.getKind() == ElementKind.FIELD || e.getKind() == ElementKind.METHOD)) {
                                    uses[0] = true;
                                }
                            }
                            return super.visitIdentifier(id, v);
                        }

                        public Void visitMemberSelect(MemberSelectTree ms, Void v) {
                            if (ms.getIdentifier().contentEquals("this")) {
                                Element x = trees.getElement(new TreePath(getCurrentPath(), ms.getExpression()));
                                if (x == h.type) {
                                    uses[0] = true;
                                }
                            }
                            return super.visitMemberSelect(ms, v);
                        }

                        public Void visitNewClass(NewClassTree nc, Void v) {
                            Element made = trees.getElement(new TreePath(getCurrentPath(), nc.getIdentifier()));
                            if (nc.getEnclosingExpression() == null && needsOuter.contains(made)) {
                                uses[0] = true;
                            }
                            return super.visitNewClass(nc, v);
                        }
                    }.scan(new TreePath(classPath, member), null);
                    if (uses[0]) {
                        needsOuter.add(ce);
                        changed = true;
                    }
                }
            }
        }
    }

    static void edit(CompilationUnitTree cu, int start, int end, String with) {
        if (start < 0 || end < 0) {
            // A tree javac made up (the type of a var or of a lambda's parameter) has no text to change.
            if (System.getenv("SPLIT_DEBUG") != null) {
                System.err.println("no position for edit '" + with + "' in " + pathOf.get(cu).getFileName() + " near " + (start < 0 ? -1 : cu.getLineMap().getLineNumber(start)));
            }
            return;
        }
        // int a, b; shares one type tree between its two declarations: the same edit comes twice.
        if (!made.add(System.identityHashCode(cu) + ":" + start + ":" + end + ":" + with)) {
            return;
        }
        edits.get(cu).add(new Edit(start, end, with, order++));
    }

    static final class Rewriter extends TreePathScanner<Void, Void> {
        final CompilationUnitTree cu;
        final String s;

        Rewriter(CompilationUnitTree cu) {
            this.cu = cu;
            this.s = text.get(cu);
        }

        /** The home of the code at a path: the host member it is inside of, or null outside every host. */
        Home site(TreePath path) {
            for (TreePath p = path; p != null && p.getParentPath() != null; p = p.getParentPath()) {
                Host h = hostByTree.get(p.getParentPath().getLeaf());
                if (h != null) {
                    return h.memberHome.get(p.getLeaf());
                }
            }
            return null;
        }

        /** Whether the code at a path sits in a class of its own inside the host member (so "this" is that class). */
        boolean nested(TreePath path) {
            for (TreePath p = path; p != null && p.getParentPath() != null; p = p.getParentPath()) {
                if (hostByTree.containsKey(p.getParentPath().getLeaf())) {
                    return false;
                }
                if (p.getLeaf() instanceof ClassTree) {
                    return true;
                }
            }
            return false;
        }

        String top(TreePath path) {
            Home home = site(path);
            if (home != null) {
                return home.cls;
            }
            TreePath p = path;
            ClassTree outermost = null;
            for (; p != null; p = p.getParentPath()) {
                if (p.getLeaf() instanceof ClassTree c && p.getParentPath() != null && p.getParentPath().getLeaf() instanceof CompilationUnitTree) {
                    outermost = c;
                }
            }
            return outermost == null ? "?" : outermost.getSimpleName().toString();
        }

        /** How code at a home reaches an instance member that lives at another. */
        String receiver(Home from, Home to, TreePath path) {
            Host h = to.host;
            if (from == null || from.host != h) {
                problems.add(where(path) + ": an instance member of " + h.name + " used without an instance from outside it");
                return "?";
            }
            if (from.core) {
                return to.field;
            }
            return to.core ? h.ctx : h.ctx + "." + to.field;
        }

        String coreInstance(Home from, Host h, TreePath path) {
            if (from == null || from.host != h) {
                problems.add(where(path) + ": needs an instance of " + h.name);
                return "?";
            }
            if (from.core) {
                return nested(path) ? h.name + ".this" : "this";
            }
            return h.ctx;
        }

        /** A class named in front of a moved member must mean that class where it is written: no nested class there has its name. */
        void visible(String cls, TreePath path) {
            for (TreePath p = path; p != null; p = p.getParentPath()) {
                if (p.getLeaf() instanceof ClassTree c) {
                    for (Tree m : c.getMembers()) {
                        if (m instanceof ClassTree nested && nested.getSimpleName().contentEquals(cls)) {
                            problems.add(where(path) + ": " + cls + " here is the class nested in " + c.getSimpleName() + ", not the new " + cls + ": give the part another name");
                        }
                    }
                }
            }
        }

        String where(TreePath path) {
            long at = pos.getStartPosition(cu, path.getLeaf());
            return pathOf.get(cu).getFileName() + ":" + cu.getLineMap().getLineNumber(at);
        }

        void priv(Element e, TreePath from) {
            if (e == null || !e.getModifiers().contains(Modifier.PRIVATE)) {
                return;
            }
            TreePath decl = trees.getPath(e);
            if (decl == null) {
                return;
            }
            CompilationUnitTree dcu = decl.getCompilationUnit();
            Rewriter other = dcu == cu ? this : new Rewriter(dcu);
            if (other.site(decl) == null && site(from) == null) {
                return;
            }
            if (other.top(decl).equals(top(from)) && dcu == cu) {
                return;
            }
            Tree leaf = decl.getLeaf();
            ModifiersTree mods = leaf instanceof MethodTree m ? m.getModifiers() : leaf instanceof VariableTree v ? v.getModifiers() : leaf instanceof ClassTree c ? c.getModifiers() : null;
            if (mods == null || !stripped.add(leaf)) {
                return;
            }
            String ds = text.get(dcu);
            int a = (int) pos.getStartPosition(dcu, mods), b = (int) pos.getEndPosition(dcu, mods);
            Matcher m = Pattern.compile("\\bprivate\\s+").matcher(ds);
            m.region(a, Math.min(ds.length(), b + 1));
            if (m.find()) {
                edit(dcu, m.start(), m.end(), "");
            } else {
                m = Pattern.compile("\\bprivate\\b").matcher(ds);
                m.region(a, Math.min(ds.length(), b + 1));
                if (m.find()) {
                    edit(dcu, m.start(), m.end(), "");
                }
            }
        }

        boolean isThisOf(ExpressionTree x, TreePath parent, Host h) {
            if (x instanceof IdentifierTree id && id.getName().contentEquals("this")) {
                Element e = trees.getElement(new TreePath(parent, x));
                return e != null && e.getEnclosingElement() == h.type;
            }
            if (x instanceof MemberSelectTree ms && ms.getIdentifier().contentEquals("this")) {
                return trees.getElement(new TreePath(new TreePath(parent, x), ms.getExpression())) == h.type;
            }
            return false;
        }

        @Override
        public Void visitVariable(VariableTree var, Void v) {
            Home from = site(getCurrentPath());
            if (from != null && !from.core && var.getName().contentEquals(from.host.ctx) && !hostByTree.containsKey(getCurrentPath().getParentPath().getLeaf())) {
                problems.add(where(getCurrentPath()) + ": a variable named " + from.host.ctx + " would hide the way back to " + from.host.name);
            }
            if (from != null && from.core && !hostByTree.containsKey(getCurrentPath().getParentPath().getLeaf())) {
                for (Home home : from.host.homes) {
                    if (!home.promoted && home.needsCtx && var.getName().contentEquals(home.field)) {
                        problems.add(where(getCurrentPath()) + ": a variable named " + home.field + " would hide the part " + home.cls);
                    }
                }
            }
            return super.visitVariable(var, v);
        }

        @Override
        public Void visitIdentifier(IdentifierTree id, Void v) {
            TreePath path = getCurrentPath();
            Element e = trees.getElement(path);
            if (e == null) {
                return null;
            }
            int a = (int) pos.getStartPosition(cu, id), b = (int) pos.getEndPosition(cu, id);
            if (e instanceof TypeElement te) {
                typeUses.get(cu).add(new Object[] { a, te });
            }
            Home from = site(path);
            if (id.getName().contentEquals("super")) {
                return null;
            }
            if (id.getName().contentEquals("this")) {
                Host h = hostByType.get(e.getEnclosingElement());
                Tree parent = path.getParentPath().getLeaf();
                boolean qualifier = parent instanceof MemberSelectTree ms && ms.getExpression() == id || parent instanceof MemberReferenceTree mr && mr.getQualifierExpression() == id
                    || parent instanceof MethodInvocationTree mi && mi.getMethodSelect() == id;
                if (h != null && from != null && !from.core && !qualifier) {
                    edit(cu, a, b, h.ctx);
                }
                return null;
            }
            priv(e, path);
            Host h = hostByType.get(e.getEnclosingElement());
            if (h == null && from != null && !from.core && !from.promoted && (e.getKind() == ElementKind.FIELD || e.getKind() == ElementKind.METHOD)
                && e.getEnclosingElement() instanceof TypeElement owner && inheritedByHost(path, from.host, owner)) {
                // A member the host inherits, used with no receiver: the part reaches it through the host.
                edit(cu, a, a, (e.getModifiers().contains(Modifier.STATIC) ? from.host.name : from.host.ctx) + ".");
                return null;
            }
            if (h == null || !h.homeOf.containsKey(e)) {
                return null;
            }
            Home to = h.homeOf.get(e);
            if (e instanceof TypeElement) {
                if (skipType.contains(id)) {
                    return null;
                }
                if (to.promoted) {
                    if (!to.cls.equals(id.getName().toString())) {
                        edit(cu, a, b, to.cls);
                    }
                    return null;
                }
                if (from != to) {
                    edit(cu, a, a, to.cls + ".");
                }
                return null;
            }
            if (e.getKind() != ElementKind.FIELD && e.getKind() != ElementKind.METHOD) {
                return null;
            }
            if (from == to) {
                return null;
            }
            if (e.getModifiers().contains(Modifier.STATIC)) {
                visible(to.cls, path);
                edit(cu, a, a, to.cls + ".");
            } else {
                edit(cu, a, a, receiver(from, to, path) + ".");
            }
            return null;
        }

        /** Whether a name with no receiver at a path means the host's inherited member: no class nearer the code has it. */
        boolean inheritedByHost(TreePath path, Host h, TypeElement owner) {
            if (!types.isSubtype(types.erasure(h.type.asType()), types.erasure(owner.asType()))) {
                return false;
            }
            for (TreePath p = path; p != null && p.getParentPath() != null; p = p.getParentPath()) {
                if (hostByTree.containsKey(p.getParentPath().getLeaf())) {
                    break;
                }
                if (p.getLeaf() instanceof ClassTree) {
                    Element c = trees.getElement(p);
                    if (c instanceof TypeElement te && types.isSubtype(types.erasure(te.asType()), types.erasure(owner.asType()))) {
                        return false;
                    }
                }
            }
            return true;
        }

        /** super.visitX(...) in code that moved out of the host: the host gets a method that makes the call. */
        boolean superBridge(MemberSelectTree ms, Element e, TreePath path) {
            Home from = site(path);
            if (!(ms.getExpression() instanceof IdentifierTree id) || !id.getName().contentEquals("super") || from == null || from.core || from.promoted || nested(path)
                || !(e instanceof ExecutableElement method)) {
                return false;
            }
            Host h = from.host;
            String name = method.getSimpleName().toString();
            String bridge = name.startsWith("visit") ? "emit" + name.substring(5) : "super" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            StringBuilder params = new StringBuilder(), args = new StringBuilder();
            int n = method.getParameters().size();
            for (int i = 0; i < n; i++) {
                VariableElement p = method.getParameters().get(i);
                String type = simple(p.asType(), h);
                if (method.isVarArgs() && i == n - 1 && type.endsWith("[]")) {
                    type = type.substring(0, type.length() - 2) + "...";
                }
                params.append(i == 0 ? "" : ", ").append(type).append(" ").append(p.getSimpleName());
                args.append(i == 0 ? "" : ", ").append(p.getSimpleName());
            }
            String ret = simple(method.getReturnType(), h);
            String text = "\n    /** " + method.getEnclosingElement().getSimpleName() + "'s own " + name + ", for the code of this class that lives in its parts. */\n    " + ret + " " + bridge + "(" + params + ") {\n        "
                + (ret.equals("void") ? "" : "return ") + "super." + name + "(" + args + ");\n    }\n";
            h.bridges.put(bridge + "(" + params + ")", text);
            int a = (int) pos.getStartPosition(cu, ms), b = (int) pos.getEndPosition(cu, ms);
            edit(cu, a, b, h.ctx + "." + bridge);
            return true;
        }

        String simple(javax.lang.model.type.TypeMirror t, Host h) {
            if (t instanceof javax.lang.model.type.ArrayType at) {
                return simple(at.getComponentType(), h) + "[]";
            }
            if (t instanceof javax.lang.model.type.DeclaredType dt && dt.asElement() instanceof TypeElement te) {
                typeUses.get(h.cu).add(new Object[] { (int) pos.getStartPosition(h.cu, h.tree), te });
                return te.getSimpleName().toString();
            }
            return t.toString();
        }

        Void member(ExpressionTree whole, ExpressionTree x, Element e, TreePath path) {
            Host h = e == null ? null : hostByType.get(e.getEnclosingElement());
            priv(e, path);
            if (h == null || !h.homeOf.containsKey(e)) {
                return scan(x, null);
            }
            Home to = h.homeOf.get(e), from = site(path);
            int xa = (int) pos.getStartPosition(cu, x), xb = (int) pos.getEndPosition(cu, x);
            Element xe = x instanceof IdentifierTree || x instanceof MemberSelectTree ? trees.getElement(new TreePath(path, x)) : null;
            if (e instanceof TypeElement te) {
                if (xe == h.type) {
                    int a = (int) pos.getStartPosition(cu, whole), b = (int) pos.getEndPosition(cu, whole);
                    String name = te.getSimpleName().toString();
                    typeUses.get(cu).add(new Object[] { a, te });
                    edit(cu, a, b, to.promoted ? to.cls : from == to ? name : to.cls + "." + name);
                    return null;
                }
                return scan(x, null);
            }
            if (e.getKind() != ElementKind.FIELD && e.getKind() != ElementKind.METHOD) {
                return scan(x, null);
            }
            if (e.getModifiers().contains(Modifier.STATIC)) {
                if (xe == h.type) {
                    if (!to.core) {
                        visible(to.cls, path);
                        edit(cu, xa, xb, to.cls);
                    }
                    return null;
                }
                if (!to.core) {
                    problems.add(where(path) + ": static " + e + " reached through an expression");
                }
                return scan(x, null);
            }
            if (isThisOf(x, path, h)) {
                if (from == to) {
                    if (!to.core && x instanceof MemberSelectTree) {
                        edit(cu, xa, xb, to.cls + ".this");
                    }
                    return null;
                }
                edit(cu, xa, xb, receiver(from, to, path));
                return null;
            }
            if (!to.core) {
                edit(cu, xb, xb, "." + to.field);
            }
            return scan(x, null);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree ms, Void v) {
            TreePath path = getCurrentPath();
            if (ms.getIdentifier().contentEquals("this")) {
                Element x = trees.getElement(new TreePath(path, ms.getExpression()));
                Host h = hostByType.get(x);
                Home from = site(path);
                Tree parent = path.getParentPath().getLeaf();
                boolean qualifier = parent instanceof MemberSelectTree p && p.getExpression() == ms || parent instanceof MemberReferenceTree mr && mr.getQualifierExpression() == ms;
                if (h != null && from != null && !from.core && !qualifier) {
                    edit(cu, (int) pos.getStartPosition(cu, ms), (int) pos.getEndPosition(cu, ms), h.ctx);
                }
                return null;
            }
            Element e = trees.getElement(path);
            if (superBridge(ms, e, path)) {
                return null;
            }
            if (e instanceof TypeElement te && !(hostByType.containsKey(e.getEnclosingElement()))) {
                // A qualified type name (java.util.Map): nothing of ours inside.
                return scan(ms.getExpression(), null);
            }
            return member(ms, ms.getExpression(), e, path);
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree mr, Void v) {
            TreePath path = getCurrentPath();
            Element e = trees.getElement(path);
            if (mr.getTypeArguments() != null) {
                scan(mr.getTypeArguments(), null);
            }
            return member(mr, mr.getQualifierExpression(), e, path);
        }

        @Override
        public Void visitNewClass(NewClassTree nc, Void v) {
            TreePath path = getCurrentPath();
            Element made = trees.getElement(new TreePath(path, nc.getIdentifier()));
            Element ctor = trees.getElement(path);
            if (nc.getClassBody() == null) {
                priv(ctor, path);
            }
            Host h = made == null ? null : hostByType.get(made.getEnclosingElement());
            if (h != null && inner(made) && h.homeOf.containsKey(made)) {
                Home to = h.homeOf.get(made), from = site(path);
                boolean needs = needsOuter.contains(made);
                if (to.promoted && needs) {
                    if (nc.getEnclosingExpression() != null) {
                        problems.add(where(path) + ": qualified creation of a promoted class");
                    }
                    String with = coreInstance(from, h, path);
                    if (nc.getArguments().isEmpty()) {
                        int open = s.indexOf('(', (int) pos.getEndPosition(cu, nc.getIdentifier()));
                        edit(cu, open + 1, open + 1, with);
                    } else {
                        int first = (int) pos.getStartPosition(cu, nc.getArguments().get(0));
                        edits.get(cu).add(new Edit(first, first, with + ", ", -1));
                    }
                } else if (!to.promoted && needs && from != to && nc.getEnclosingExpression() == null) {
                    int at = (int) pos.getStartPosition(cu, nc);
                    edit(cu, at, at, receiver(from, to, path) + ".");
                    skipType.add(nc.getIdentifier());
                }
                if (nc.getEnclosingExpression() != null) {
                    if (needs) {
                        skipType.add(nc.getIdentifier());
                    } else {
                        // The class no longer takes an instance (it never used one): x.new Mod() is new Mod().
                        int start = (int) pos.getStartPosition(cu, nc);
                        edit(cu, start, s.indexOf("new", (int) pos.getEndPosition(cu, nc.getEnclosingExpression())), "");
                    }
                }
            }
            return super.visitNewClass(nc, v);
        }
    }

    /** A promoted class that uses the host's instance is handed it: a field, and a first parameter of each constructor. */
    static void ctors(Host h) {
        String s = text.get(h.cu);
        TreePath classPath = trees.getPath(h.type);
        for (Tree member : h.tree.getMembers()) {
            if (!(member instanceof ClassTree c)) {
                continue;
            }
            Element ce = trees.getElement(new TreePath(classPath, member));
            Home home = h.memberHome.get(member);
            int start = (int) pos.getStartPosition(h.cu, c);
            if (!needsOuter.contains(ce) && !home.promoted && inner(ce)) {
                ModifiersTree mods = c.getModifiers();
                int at = s.indexOf("class " + c.getSimpleName(), start);
                Matcher fin = Pattern.compile("(final\\s+|abstract\\s+)*class " + Pattern.quote(c.getSimpleName().toString())).matcher(s);
                fin.region(start, s.length());
                if (fin.find()) {
                    at = fin.start();
                }
                edit(h.cu, at, at, "static ");
                continue;
            }
            if (!home.promoted) {
                continue;
            }
            int head = s.indexOf(c.getKind() == Tree.Kind.CLASS ? "class " : c.getKind() == Tree.Kind.INTERFACE ? "interface " : c.getKind() == Tree.Kind.RECORD ? "record " : "enum ", start);
            // A top-level class is neither static nor private.
            Matcher mod = Pattern.compile("\\b(static|private|protected)\\s+").matcher(s);
            mod.region(start, head);
            while (mod.find()) {
                edit(h.cu, mod.start(), mod.end(), "");
            }
            String old = c.getSimpleName().toString();
            if (!home.cls.equals(old)) {
                int at = s.indexOf(old, head);
                edit(h.cu, at, at + old.length(), home.cls);
                for (Tree m : c.getMembers()) {
                    if (m instanceof MethodTree mt && mt.getName().contentEquals("<init>")) {
                        int ms = (int) pos.getStartPosition(h.cu, mt);
                        if (ms >= 0 && explicitCtor(s, ms, old)) {
                            int paren = s.indexOf('(', ms);
                            int name = s.lastIndexOf(old, paren);
                            edit(h.cu, name, name + old.length(), home.cls);
                        }
                    }
                }
            }
            if (!needsOuter.contains(ce)) {
                continue;
            }
            int open = s.indexOf('{', head);
            String indent = "        ";
            StringBuilder field = new StringBuilder("\n" + indent + "final " + h.name + " " + h.ctx + ";\n");
            boolean any = false;
            for (Tree m : c.getMembers()) {
                if (!(m instanceof MethodTree mt) || !mt.getName().contentEquals("<init>")) {
                    continue;
                }
                int ms = (int) pos.getStartPosition(h.cu, mt);
                if (ms < 0 || !explicitCtor(s, ms, c.getSimpleName().toString())) {
                    continue;
                }
                any = true;
                int paren = s.indexOf('(', ms);
                // Before whatever is written in front of the first parameter's own type.
                edits.get(h.cu).add(new Edit(paren + 1, paren + 1, h.name + " " + h.ctx + (mt.getParameters().isEmpty() ? "" : ", "), -1));
                List<? extends StatementTree> body = mt.getBody().getStatements();
                StatementTree first = body.isEmpty() ? null : body.get(0);
                String called = first instanceof ExpressionStatementTree es && es.getExpression() instanceof MethodInvocationTree mi && mi.getMethodSelect() instanceof IdentifierTree id ? id.getName().toString() : "";
                // javac writes super() into a constructor that calls neither: that one has no text.
                int firstAt = first == null ? -1 : (int) pos.getStartPosition(h.cu, first);
                if (!called.isEmpty() && (pos.getEndPosition(h.cu, first) < 0 || !s.startsWith(called, firstAt))) {
                    called = "";
                }
                if (called.equals("this")) {
                    MethodInvocationTree mi = (MethodInvocationTree) ((ExpressionStatementTree) first).getExpression();
                    int p = s.indexOf('(', (int) pos.getStartPosition(h.cu, mi));
                    edits.get(h.cu).add(new Edit(p + 1, p + 1, h.ctx + (mi.getArguments().isEmpty() ? "" : ", "), -1));
                    continue;
                }
                String assign = indent + "    this." + h.ctx + " = " + h.ctx + ";\n";
                if (called.equals("super")) {
                    int after = lineEnd(s, (int) pos.getEndPosition(h.cu, first));
                    edit(h.cu, after, after, assign);
                } else {
                    int after = lineEnd(s, s.indexOf('{', (int) pos.getStartPosition(h.cu, mt.getBody())));
                    edit(h.cu, after, after, assign);
                }
            }
            if (!any) {
                field.append("\n" + indent + home.cls + "(" + h.name + " " + h.ctx + ") {\n" + indent + "    this." + h.ctx + " = " + h.ctx + ";\n" + indent + "}\n");
            }
            int at = lineEnd(s, open);
            edit(h.cu, at, at, field.substring(1) + (any ? "\n" : "\n"));
        }
    }

    static String apply(CompilationUnitTree cu, int from, int to) {
        String s = text.get(cu);
        List<Edit> in = new ArrayList<>();
        for (Edit e : edits.get(cu)) {
            if (e.start >= from && e.end <= to) {
                in.add(e);
            }
        }
        in.sort(Comparator.comparingInt((Edit e) -> e.start).thenComparingInt(e -> e.order));
        StringBuilder out = new StringBuilder();
        int at = from;
        for (Edit e : in) {
            if (e.start < at) {
                throw new IllegalStateException("overlapping edits in " + pathOf.get(cu) + " at " + cu.getLineMap().getLineNumber(e.start) + ": " + e.text);
            }
            out.append(s, at, e.start).append(e.text);
            at = e.end;
        }
        out.append(s, at, to);
        return out.toString();
    }

    static String imports(CompilationUnitTree cu, List<int[]> ranges) {
        String s = text.get(cu);
        Set<String> names = new HashSet<>(), owners = new HashSet<>();
        for (Object[] use : typeUses.get(cu)) {
            int at = (Integer) use[0];
            for (int[] r : ranges) {
                if (at >= r[0] && at < r[1]) {
                    TypeElement te = (TypeElement) use[1];
                    names.add(te.getQualifiedName().toString());
                    Element owner = te.getEnclosingElement();
                    owners.add(owner instanceof QualifiedNameable q ? q.getQualifiedName().toString() : "");
                }
            }
        }
        StringBuilder out = new StringBuilder();
        for (ImportTree imp : cu.getImports()) {
            String name = imp.getQualifiedIdentifier().toString();
            boolean keep = imp.isStatic() || (name.endsWith(".*") ? owners.contains(name.substring(0, name.length() - 2)) : names.contains(name));
            if (keep) {
                out.append(s, (int) pos.getStartPosition(cu, imp), (int) pos.getEndPosition(cu, imp)).append("\n");
            }
        }
        return out.toString();
    }

    static String dedent(String block) {
        StringBuilder out = new StringBuilder();
        for (String line : block.split("\n", -1)) {
            out.append(line.startsWith("    ") ? line.substring(4) : line).append("\n");
        }
        out.setLength(out.length() - 1);
        return out.toString();
    }

    static void write(Path file, String content, boolean crlf) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        content = content.replaceAll("\n{3,}", "\n\n");
        Files.writeString(file, crlf ? content.replace("\n", "\r\n") : content);
    }

    static void emit(CompilationUnitTree cu, boolean crlf) throws IOException {
        String s = text.get(cu);
        Host host = null;
        for (Host h : hosts) {
            if (h.cu == cu) {
                if (host != null) {
                    throw new IllegalStateException("two hosts in one file");
                }
                host = h;
            }
        }
        if (host == null) {
            if (!edits.get(cu).isEmpty()) {
                write(pathOf.get(cu), apply(cu, 0, s.length()), crlf);
            }
            return;
        }
        List<int[]> moved = new ArrayList<>();
        for (Home home : host.homes) {
            if (home.ranges.isEmpty()) {
                continue;
            }
            moved.addAll(home.ranges);
            StringBuilder body = new StringBuilder();
            for (int[] r : home.ranges) {
                body.append(apply(cu, r[0], r[1]));
            }
            StringBuilder out = new StringBuilder(imports(cu, home.ranges));
            if (out.length() > 0) {
                out.append("\n");
            }
            if (home.promoted) {
                String b = dedent(body.toString());
                out.append(b.replaceFirst("^\n+", ""));
            } else {
                if (home.doc != null) {
                    out.append("/** ").append(home.doc).append(" */\n");
                }
                out.append("final class ").append(home.cls).append(" {\n");
                if (home.needsCtx) {
                    out.append("\n    final ").append(host.name).append(" ").append(host.ctx).append(";\n\n    ").append(home.cls).append("(").append(host.name).append(" ").append(host.ctx).append(") {\n        this.")
                        .append(host.ctx).append(" = ").append(host.ctx).append(";\n    }\n");
                }
                String b = body.toString();
                if (!b.startsWith("\n")) {
                    out.append("\n");
                }
                out.append(b).append("}\n");
            }
            write(Path.of(home.file), out.toString(), crlf);
        }
        // What is left of the host: everything not moved, with its parts as fields under the class's opening brace.
        moved.sort(Comparator.comparingInt(r -> r[0]));
        int headStart = cu.getImports().isEmpty() ? 0 : (int) pos.getStartPosition(cu, cu.getImports().get(0));
        int headEnd = cu.getImports().isEmpty() ? 0 : lineEnd(s, (int) pos.getEndPosition(cu, cu.getImports().get(cu.getImports().size() - 1)));
        List<int[]> kept = new ArrayList<>();
        int at = headEnd;
        for (int[] r : moved) {
            kept.add(new int[] { at, r[0] });
            at = r[1];
        }
        kept.add(new int[] { at, s.length() });
        int open = lineEnd(s, s.indexOf('{', s.indexOf(host.name, (int) pos.getStartPosition(cu, host.tree))));
        StringBuilder out = new StringBuilder(s.substring(0, headStart));
        out.append(imports(cu, kept));
        StringBuilder fields = new StringBuilder();
        for (Home home : host.homes) {
            if (!home.promoted && home.needsCtx && !home.ranges.isEmpty()) {
                fields.append("    final ").append(home.cls).append(" ").append(home.field).append(" = new ").append(home.cls).append("(this);\n");
            }
        }
        int close = (int) pos.getEndPosition(cu, host.tree) - 1;
        for (String key : host.bridges.keySet()) {
            for (Tree m : host.tree.getMembers()) {
                if (m instanceof MethodTree mt && key.startsWith(mt.getName() + "(")) {
                    throw new IllegalStateException(host.name + " already has a method named like the bridge " + key);
                }
            }
        }
        if (!host.bridges.isEmpty()) {
            edits.get(cu).add(new Edit(close, close, String.join("", host.bridges.values()), Integer.MAX_VALUE));
        }
        for (int[] r : kept) {
            if (r[0] <= open && open <= r[1] && fields.length() > 0) {
                out.append(apply(cu, r[0], open)).append("\n").append(fields).append(apply(cu, open, r[1]));
                fields.setLength(0);
            } else {
                out.append(apply(cu, r[0], r[1]));
            }
        }
        write(pathOf.get(cu), out.toString(), crlf);
    }

    /** What each member of each host is and which of the host's other members it uses, for drawing up a plan. */
    static void survey(Path out) throws IOException {
        StringBuilder json = new StringBuilder("{\n");
        for (Host h : hosts) {
            String s = text.get(h.cu);
            TreePath classPath = trees.getPath(h.type);
            json.append("\"").append(h.name).append("\": [\n");
            Map<String, Set<String>> uses = new LinkedHashMap<>();
            Map<String, String> info = new LinkedHashMap<>();
            for (Tree member : h.tree.getMembers()) {
                int start = (int) pos.getStartPosition(h.cu, member), end = (int) pos.getEndPosition(h.cu, member);
                if (start < 0 || end < 0) {
                    continue;
                }
                Element me = trees.getElement(new TreePath(classPath, member));
                String key = key(member, me);
                if (member instanceof MethodTree m && m.getName().contentEquals("<init>") && !explicitCtor(s, start, h.name)) {
                    continue;
                }
                Set<String> set = uses.computeIfAbsent(key, k -> new TreeSet<>());
                new TreePathScanner<Void, Void>() {
                    void use(Element e) {
                        if (e != null && e.getEnclosingElement() == h.type && e != me) {
                            String k = e instanceof ExecutableElement x ? x.getSimpleName() + "()" : e.getSimpleName().toString();
                            if (!k.equals(key) && !k.equals("<init>()")) {
                                set.add(k);
                            }
                        }
                    }

                    public Void visitIdentifier(IdentifierTree id, Void v) {
                        use(trees.getElement(getCurrentPath()));
                        return null;
                    }

                    public Void visitMemberSelect(MemberSelectTree ms, Void v) {
                        use(trees.getElement(getCurrentPath()));
                        return super.visitMemberSelect(ms, v);
                    }

                    public Void visitMemberReference(MemberReferenceTree mr, Void v) {
                        use(trees.getElement(getCurrentPath()));
                        return super.visitMemberReference(mr, v);
                    }
                }.scan(new TreePath(classPath, member), null);
                boolean isStatic = me != null && me.getModifiers().contains(Modifier.STATIC);
                long line = h.cu.getLineMap().getLineNumber(start), last = h.cu.getLineMap().getLineNumber(end);
                String kind = member instanceof MethodTree ? "method" : member instanceof VariableTree ? "field" : member instanceof ClassTree ? "class" : "block";
                String prev = info.get(key);
                long lines = last - line + 1;
                if (prev != null) {
                    Matcher m = Pattern.compile("\"lines\": (\\d+)").matcher(prev);
                    m.find();
                    lines += Long.parseLong(m.group(1));
                    line = Long.parseLong(prev.replaceAll(".*\"line\": (\\d+).*", "$1"));
                }
                info.put(key, "\"kind\": \"" + kind + "\", \"static\": " + isStatic + ", \"outer\": " + needsOuter.contains(me) + ", \"line\": " + line + ", \"lines\": " + lines);
            }
            int n = 0;
            for (var e : info.entrySet()) {
                json.append("  {\"key\": \"").append(e.getKey()).append("\", ").append(e.getValue()).append(", \"uses\": [");
                int k = 0;
                for (String u : uses.get(e.getKey())) {
                    json.append(k++ == 0 ? "" : ", ").append("\"").append(u).append("\"");
                }
                json.append("]}").append(++n < info.size() ? ",\n" : "\n");
            }
            json.append(h == hosts.get(hosts.size() - 1) ? "]\n" : "],\n");
        }
        json.append("}\n");
        Files.writeString(out, json.toString());
    }
}
