import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

// ------------------------------------------------------------------ apply the mechanical part

/**
 * Rewrites what the map makes certain: moved or renamed classes everywhere they are imported
 * (javac stops at 100 errors, so waiting for each one costs rounds), and renamed methods and
 * fields exactly where javac reported them. Prints "map-fix: N replacement(s)".
 */
final class MapFix {

    static final Set<String> EXCLUDED = Set.of(
        "build",
        ".gradle",
        ".git",
        "run",
        ".mmt",
        ".tools",
        ".runs",
        ".lab",
        "node_modules",
        "out"
    );

    static Path sourcePath(VersionMap.Err error, Path repo) {
        Path path = Path.of(error.file());
        if (!path.isAbsolute()) {
            path = repo.resolve(path);
        }
        path = path.toAbsolutePath().normalize();
        if (!path.startsWith(repo.toAbsolutePath().normalize())) {
            return null;
        }
        for (Path part : repo.toAbsolutePath().normalize().relativize(path)) if (
            EXCLUDED.contains(part.toString())
        ) {
            return null;
        }
        try {
            return path.toRealPath().startsWith(repo.toRealPath()) ? path : null;
        } catch (IOException missing) {
            return null;
        }
    }

    static List<Path> sourceFiles(Path repo) throws IOException {
        List<Path> result = new ArrayList<>();
        Files.walkFileTree(
            repo,
            new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(
                    Path dir,
                    java.nio.file.attribute.BasicFileAttributes attrs
                ) {
                    // A folder with its own settings.gradle is another Gradle build, not part of this port.
                    boolean separate =
                        !dir.getFileName().toString().equals("buildSrc") &&
                        !dir.getFileName().toString().equals("build-logic") &&
                        (Files.isRegularFile(dir.resolve("settings.gradle")) || Files.isRegularFile(dir.resolve("settings.gradle.kts")));
                    return (
                            !dir.equals(repo) &&
                            (EXCLUDED.contains(dir.getFileName().toString()) || Files.exists(dir.resolve(".git")) || separate)
                        )
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && file.toString().endsWith(".java")) {
                        result.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            }
        );
        return result;
    }

    /** The source that declares TYPE: this file when it does, else the first project file that does (or this file). */
    static String declaring(String text, String type, List<Path> files) throws IOException {
        if (type == null) {
            return text;
        }
        Pattern declared = Pattern.compile("\\bclass\\s+" + Pattern.quote(type) + "\\b");
        if (declared.matcher(text).find()) {
            return text;
        }
        for (Path f : files) {
            if (!f.getFileName().toString().equals(type + ".java")) {
                continue;
            }
            String other = Files.readString(f, StandardCharsets.UTF_8);
            if (declared.matcher(other).find()) {
                return other;
            }
        }
        return text;
    }

    /** Whether the file declares TYPE as extending a class it imports from the game (net.minecraft or com.mojang). */
    /**
     * The call to name on the error's line rewritten to the game's own replacement, or null when no candidate fits
     * exactly one way: its old parameter count must match the call, and every argument of the replacement must come
     * from the call (the same types in order, some dropped, or the receiver passed in or taken out).
     */
    /**
     * The member name on the error's line (from the caret on) replaced by the chain: fields as written, methods with
     * "()" except a last method step when the old member was a method (it keeps the call's own arguments). A field
     * that is assigned (=, +=, ++) is left alone: a chain ending in a call cannot be assigned.
     */
    static String applyChain(String text, VersionMap.Err e, String name, boolean method, String steps) {
        int lineStart = 0;
        for (int i = 1; i < e.line() && lineStart > -1; i++) {
            int next = text.indexOf('\n', lineStart);
            lineStart = next < 0 ? -1 : next + 1;
        }
        if (lineStart < 0) {
            return null;
        }
        int lineEnd = text.indexOf('\n', lineStart);
        lineEnd = lineEnd < 0 ? text.length() : lineEnd;
        Matcher use = Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + (method ? "(?=\\s*\\()" : "(?![\\w$]|\\s*\\()")).matcher(text);
        use.region(Math.min(lineEnd, lineStart + Math.max(0, e.caret())), lineEnd);
        if (!use.find()) {
            use.region(lineStart, lineEnd);
            if (!use.find()) {
                return null;
            }
        }
        String after = text.substring(use.end(), lineEnd).stripLeading();
        if (!method && (after.matches("^(=[^=]|\\+\\+|--|[-+*/%&|^]=|<<=|>>=).*") || after.equals("="))) {
            return null;
        }
        String[] parts = steps.split(",");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            boolean isMethod = parts[i].startsWith("m:");
            out.append(i == 0 ? "" : ".").append(parts[i].substring(2));
            if (isMethod && !(method && i == parts.length - 1)) {
                out.append("()");
            }
        }
        return text.substring(0, use.start()) + out + text.substring(use.end());
    }

    static String applyRewrite(String text, VersionMap.Err e, String name, List<VersionMap.Rewrite> candidates, MapKnown k, Set<String> importsOut) {
        int lineStart = 0;
        for (int i = 1; i < e.line() && lineStart > -1; i++) {
            int next = text.indexOf('\n', lineStart);
            lineStart = next < 0 ? -1 : next + 1;
        }
        if (lineStart < 0) {
            return null;
        }
        int lineEnd = text.indexOf('\n', lineStart);
        lineEnd = lineEnd < 0 ? text.length() : lineEnd;
        Matcher call = Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "\\s*\\(").matcher(text);
        call.region(Math.min(lineEnd, lineStart + Math.max(0, e.caret())), lineEnd);
        if (!call.find()) {
            call.region(lineStart, lineEnd);
            if (!call.find()) {
                return null;
            }
        }
        int open = call.end() - 1;
        int close = closingParen(text, open);
        if (close < 0) {
            return null;
        }
        List<String> args = new ArrayList<>();
        for (String a : topLevelArguments(text.substring(open + 1, close))) {
            args.add(a.strip());
        }
        int before = call.start() - 1;
        while (before >= 0 && Character.isWhitespace(text.charAt(before))) {
            before--;
        }
        boolean qualified = before >= 0 && text.charAt(before) == '.';
        int start = qualified ? receiverStart(text, before) : call.start();
        String receiver = qualified ? text.substring(start, before).strip() : null;
        // A call the game deleted (no replacement): only a no-argument void call that is a whole statement goes, so
        // nothing a mod passed in is lost (1.21.2: RenderSystem.applyModelViewMatrix();).
        for (VersionMap.Rewrite r : candidates) {
            if (!r.to().isEmpty() || !"()V".equals(r.desc()) || !args.isEmpty()) {
                continue;
            }
            int before2 = start - 1;
            while (before2 >= 0 && (text.charAt(before2) == ' ' || text.charAt(before2) == '\t')) {
                before2--;
            }
            int after = close + 1;
            while (after < text.length() && Character.isWhitespace(text.charAt(after)) && text.charAt(after) != '\n') {
                after++;
            }
            boolean statementStart = before2 < 0 || "\n;{}".indexOf(text.charAt(before2)) >= 0;
            if (statementStart && after < text.length() && text.charAt(after) == ';') {
                return text.substring(0, start) + text.substring(after + 1);
            }
            return null;
        }
        Set<String> replacements = new LinkedHashSet<>();
        Set<String> imports = new LinkedHashSet<>();
        for (VersionMap.Rewrite r : candidates) {
            if (r.to().isEmpty()) {
                continue;
            }
            List<String> old = paramTypes(r.desc());
            old.replaceAll(t -> k.classFqn.getOrDefault(t, t));
            if (old.size() != args.size()) {
                continue;
            }
            List<String> neu = paramTypes(r.newDesc());
            String oldOwner = k.classFqn.getOrDefault(r.owner(), r.owner());
            String qualifier = r.toOwner().substring(r.toOwner().lastIndexOf('.') + 1).replace('$', '.');
            String outer = r.toOwner().replaceAll("\\$.*", "");
            String built = null;
            // A link from the game's map records no static-ness: a call written on the old class's name is static.
            boolean wasStatic = r.knownStatic() ? r.isStatic() : qualified && receiver != null && receiver.equals(VersionMap.simple(r.owner()));
            boolean nowStatic = r.knownStatic() ? r.newStatic() : wasStatic;
            if (!wasStatic && !nowStatic) {
                List<String> mapped = mapArguments(old, neu, args);
                if (mapped != null) {
                    built = (qualified ? receiver + "." : "") + r.to() + "(" + String.join(", ", mapped) + ")";
                }
            } else if (wasStatic && nowStatic) {
                List<String> mapped = mapArguments(old, neu, args);
                if (mapped != null) {
                    built = qualifier + "." + r.to() + "(" + String.join(", ", mapped) + ")";
                    imports.add(outer);
                }
            } else if (!wasStatic && nowStatic) {
                // The receiver becomes the first argument (its type leads the new parameters), or the game no longer needs it.
                List<String> withReceiver = new ArrayList<>(List.of(oldOwner));
                withReceiver.addAll(old);
                List<String> callArgs = new ArrayList<>(List.of(receiver == null ? "this" : receiver));
                callArgs.addAll(args);
                List<String> mapped = !neu.isEmpty() && sameType(neu.get(0), oldOwner) ? mapArguments(withReceiver, neu, callArgs) : mapArguments(old, neu, args);
                if (mapped != null) {
                    built = qualifier + "." + r.to() + "(" + String.join(", ", mapped) + ")";
                    imports.add(outer);
                }
            } else if (!old.isEmpty() && sameType(old.get(0), k.classFqn.getOrDefault(r.toOwner(), r.toOwner()))) {
                // A static helper became a method of its first argument's type.
                List<String> mapped = mapArguments(old.subList(1, old.size()), neu, args.subList(1, args.size()));
                if (mapped != null) {
                    String self = args.get(0).matches("[\\w.$]+(\\(\\))?") ? args.get(0) : "(" + args.get(0) + ")";
                    built = self + "." + r.to() + "(" + String.join(", ", mapped) + ")";
                }
            }
            if (built != null) {
                replacements.add(built);
            }
        }
        if (replacements.size() != 1) {
            return null;
        }
        // Imports wait until every error of the file is rewritten: an import line now would shift the lines below.
        importsOut.addAll(imports);
        return text.substring(0, start) + replacements.iterator().next() + text.substring(close + 1);
    }

    static boolean sameType(String a, String b) {
        return a.equals(b) || VersionMap.simple(a).equals(VersionMap.simple(b));
    }

    /** The call's arguments for the new parameters: the same types, some dropped (one way only), or reordered (one way only). */
    static List<String> mapArguments(List<String> old, List<String> neu, List<String> args) {
        if (old.size() == neu.size()) {
            boolean same = true;
            for (int i = 0; i < old.size(); i++) {
                same &= sameType(old.get(i), neu.get(i));
            }
            if (same) {
                return new ArrayList<>(args);
            }
        }
        if (neu.size() < old.size()) {
            List<String> simpleOld = new ArrayList<>(), simpleNew = new ArrayList<>();
            for (String t : old) {
                simpleOld.add(VersionMap.simple(t));
            }
            for (String t : neu) {
                simpleNew.add(VersionMap.simple(t));
            }
            int[] kept = uniqueEmbedding(simpleOld, simpleNew);
            if (kept != null) {
                List<String> out = new ArrayList<>();
                for (int i : kept) {
                    out.add(args.get(i));
                }
                return out;
            }
        }
        return null;
    }

    /** Where the receiver expression ending just before the '.' at dot starts: names, calls and indexes chained with dots. */
    static int receiverStart(String text, int dot) {
        int i = dot - 1;
        while (true) {
            while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
                i--;
            }
            if (i < 0) {
                return 0;
            }
            char ch = text.charAt(i);
            if (ch == ')' || ch == ']') {
                char openCh = ch == ')' ? '(' : '[';
                int depth = 0;
                int o = i;
                for (; o >= 0; o--) {
                    char c = text.charAt(o);
                    if (c == ch) {
                        depth++;
                    } else if (c == openCh && --depth == 0) {
                        break;
                    }
                }
                if (o < 0) {
                    return 0;
                }
                int p = o - 1;
                while (p >= 0 && Character.isWhitespace(text.charAt(p))) {
                    p--;
                }
                if (p < 0 || !Character.isJavaIdentifierPart(text.charAt(p))) {
                    return o; // a parenthesised expression starts the receiver
                }
                i = p; // the name of the call (or the array) before the brackets
            }
            while (i >= 0 && Character.isJavaIdentifierPart(text.charAt(i))) {
                i--;
            }
            int startOfName = i + 1;
            int j = i;
            while (j >= 0 && Character.isWhitespace(text.charAt(j))) {
                j--;
            }
            if (j >= 0 && text.charAt(j) == '.') {
                i = j - 1;
                continue;
            }
            return startOfName;
        }
    }

    /** The source with an import of fqn (dotted), unless it already imports it or the class is in the file's own package. */
    static String withImport(String text, String fqn) {
        String pkg = fqn.contains(".") ? fqn.substring(0, fqn.lastIndexOf('.')) : "";
        if (Pattern.compile("(?m)^import\\s+" + Pattern.quote(fqn) + "\\s*;").matcher(text).find()) {
            return text;
        }
        if (Pattern.compile("(?m)^package\\s+" + Pattern.quote(pkg) + "\\s*;").matcher(text).find()) {
            return text;
        }
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        Matcher last = Pattern.compile("(?m)^import\\s+[\\w.]+(?:\\.\\*)?\\s*;[^\\n]*$").matcher(text);
        int at = -1;
        while (last.find()) {
            at = last.end();
        }
        if (at < 0) {
            Matcher p = Pattern.compile("(?m)^package\\s+[\\w.]+\\s*;[^\\n]*$").matcher(text);
            if (!p.find()) {
                return "import " + fqn + ";" + nl + text;
            }
            at = p.end();
        }
        return text.substring(0, at) + nl + "import " + fqn + ";" + text.substring(at);
    }

    /**
     * The @Override declared at (or just below) the error's line with the parameter list of the supertype method's new
     * signature, or null. The class is the nearest one declared above; its supertypes come from --supers.
     */
    static String overrideSignature(String text, VersionMap.Err e, MapKnown k, Set<String> importsOut) {
        String[] lines = text.split("\r?\n", -1);
        String enclosing = null;
        for (int back = Math.min(lines.length, e.line()) - 1; back >= 0 && enclosing == null; back--) {
            Matcher type = Pattern.compile("\\b(?:class|enum|record)\\s+(\\w+)").matcher(lines[back]);
            if (type.find()) {
                enclosing = type.group(1);
            }
        }
        Set<String> supers = enclosing == null ? null : VersionMap.SUPERS.get(enclosing);
        if (supers == null) {
            return null;
        }
        int lineStart = 0;
        for (int i = 1; i < e.line(); i++) {
            lineStart = text.indexOf('\n', lineStart) + 1;
        }
        Matcher declared = Pattern.compile(
            "(?:(?:public|protected|private|static|final|synchronized)\\s+)*([\\w.<>\\[\\], ?]+?)\\s+(\\w+)\\s*\\("
        ).matcher(text);
        declared.region(lineStart, Math.min(text.length(), lineStart + 600));
        if (!declared.find() || text.substring(lineStart, declared.start()).contains(";")) {
            return null;
        }
        String name = declared.group(2);
        int open = declared.end() - 1;
        int close = closingParen(text, open);
        if (close < 0) {
            return null;
        }
        List<String[]> params = new ArrayList<>(); // {type, name}
        for (String p : topLevelArguments(text.substring(open + 1, close))) {
            String[] words = p.strip().replaceAll("@\\w+(\\([^)]*\\))?\\s*", "").replaceAll("\\bfinal\\s+", "").split("\\s+");
            if (words.length < 2) {
                return null;
            }
            params.add(new String[] { String.join(" ", Arrays.copyOf(words, words.length - 1)), words[words.length - 1] });
        }
        Set<String> fits = new LinkedHashSet<>();
        String newDescKept = null;
        for (String owner : supers) {
            for (String transition : k.sigTransitions.getOrDefault(owner + "." + name, Set.of())) {
                String[] t = transition.split(" -> ");
                if (t.length != 2 || !t[0].startsWith("(") || !t[1].startsWith("(")) {
                    continue;
                }
                String oldRet = returnType(t[0]);
                if (!k.classFqn.getOrDefault(oldRet, oldRet).equals(returnType(t[1]))) {
                    continue; // the return type changed beyond the map's rename: not a parameter change alone
                }
                List<String> old = paramTypes(t[0]);
                if (old.size() != params.size()) {
                    continue;
                }
                boolean same = true;
                for (int i = 0; i < old.size(); i++) {
                    String declaredType = params.get(i)[0].replaceAll("<.*", "").replaceAll(".*\\.", "");
                    String oldType = javaTypeName(k.classFqn.getOrDefault(old.get(i), old.get(i)));
                    String oldOriginal = javaTypeName(old.get(i));
                    same &= declaredType.equals(oldType) || declaredType.equals(oldOriginal);
                }
                if (same) {
                    fits.add(t[0] + " -> " + t[1]);
                    newDescKept = t[1];
                }
            }
        }
        if (fits.size() != 1) {
            return null;
        }
        List<String> old = paramTypes(fits.iterator().next().split(" -> ")[0]);
        List<String> neu = paramTypes(newDescKept);
        // Which new parameter each old one became: the same type (in order, one way), or, with as many parameters,
        // the same place (a type that changed in place).
        List<String> oldSimple = new ArrayList<>(), newSimple = new ArrayList<>();
        for (String t : old) {
            oldSimple.add(javaTypeName(k.classFqn.getOrDefault(t, t)));
        }
        for (String t : neu) {
            newSimple.add(javaTypeName(t));
        }
        int[] at = new int[old.size()];
        if (neu.size() > old.size()) {
            int[] kept = uniqueEmbedding(newSimple, oldSimple);
            if (kept == null) {
                return null;
            }
            at = kept;
        } else if (neu.size() == old.size()) {
            for (int i = 0; i < at.length; i++) {
                at[i] = i;
            }
        } else {
            // Parameters the game dropped (1.21.2: propagatesSkylightDown(BlockState, BlockGetter, BlockPos) ->
            // (BlockState)): the declaration loses them only when the body never names them.
            int[] kept = uniqueEmbedding(oldSimple, newSimple);
            if (kept == null) {
                return null;
            }
            int bodyStart = text.indexOf('{', close), bodyEnd = bodyStart < 0 ? -1 : closingBrace(text, bodyStart);
            String methodBody = bodyStart < 0 || bodyEnd < 0 ? "" : text.substring(bodyStart, bodyEnd + 1);
            Set<Integer> keptOld = new HashSet<>();
            for (int i : kept) {
                keptOld.add(i);
            }
            List<String> declaration = new ArrayList<>();
            for (int i = 0; i < params.size(); i++) {
                if (keptOld.contains(i)) {
                    declaration.add(params.get(i)[0] + " " + params.get(i)[1]);
                } else if (Pattern.compile("(?<![\\w$.])" + Pattern.quote(params.get(i)[1]) + "(?![\\w$])").matcher(methodBody).find()) {
                    return null; // the body still uses what the game no longer passes
                }
            }
            return text.substring(0, open + 1) + String.join(", ", declaration) + text.substring(close);
        }
        String[] names = new String[neu.size()];
        String[] types = new String[neu.size()];
        Set<String> used = new HashSet<>();
        for (String[] p : params) {
            used.add(p[1]);
        }
        for (int i = 0; i < old.size(); i++) {
            names[at[i]] = params.get(i)[1];
            boolean changed = !oldSimple.get(i).equals(newSimple.get(at[i]));
            types[at[i]] = changed ? newSimple.get(at[i]) : params.get(i)[0];
            if (changed && neu.get(at[i]).contains(".")) {
                importsOut.add(neu.get(at[i]).replaceAll("\\$.*", ""));
            }
        }
        for (int j = 0; j < neu.size(); j++) {
            if (names[j] != null) {
                continue;
            }
            String type = newSimple.get(j);
            String fresh = Character.toLowerCase(type.charAt(0)) + type.substring(1).replaceAll("\\W", "");
            if (javax.lang.model.SourceVersion.isKeyword(fresh)) {
                fresh = "mmt" + Character.toUpperCase(fresh.charAt(0)) + fresh.substring(1); // long -> mmtLong
            }
            while (used.contains(fresh)) {
                fresh = fresh + "2";
            }
            used.add(fresh);
            names[j] = fresh;
            types[j] = type;
            if (neu.get(j).contains(".")) {
                importsOut.add(neu.get(j).replaceAll("\\$.*", ""));
            }
        }
        List<String> declaration = new ArrayList<>();
        for (int j = 0; j < neu.size(); j++) {
            declaration.add(types[j] + " " + names[j]);
        }
        // super.name(...) inside the method passes the new parameters too.
        int bodyOpen = text.indexOf('{', close);
        int bodyClose = bodyOpen < 0 ? -1 : closingBrace(text, bodyOpen);
        String body = bodyOpen < 0 || bodyClose < 0 ? "" : text.substring(bodyOpen, bodyClose + 1);
        StringBuilder newBody = new StringBuilder();
        Matcher superCall = Pattern.compile("\\bsuper\\s*\\.\\s*" + Pattern.quote(name) + "\\s*\\(").matcher(body);
        int done = 0;
        while (superCall.find()) {
            int callOpen = superCall.end() - 1;
            int callClose = closingParen(body, callOpen);
            if (callClose < 0) {
                break;
            }
            List<String> args = new ArrayList<>();
            for (String a : topLevelArguments(body.substring(callOpen + 1, callClose))) {
                args.add(a.strip());
            }
            if (args.size() != old.size()) {
                continue;
            }
            String[] passed = names.clone();
            for (int i = 0; i < old.size(); i++) {
                passed[at[i]] = args.get(i);
            }
            newBody.append(body, done, callOpen + 1).append(String.join(", ", passed)).append(')');
            done = callClose + 1;
        }
        newBody.append(body.substring(done));
        String out = text.substring(0, open + 1) + String.join(", ", declaration) + text.substring(close, bodyOpen < 0 ? close : bodyOpen);
        return bodyOpen < 0 ? out + text.substring(close) : out + newBody + text.substring(bodyClose + 1);
    }

    static int closingBrace(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') {
                i = stringEnd(text, i);
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** A descriptor type as javac prints it in a message: double, Vec3, int[], Inner (nested classes by their own name). */
    static String javaTypeName(String descType) {
        String dims = "";
        while (descType.startsWith("[")) {
            dims += "[]";
            descType = descType.substring(1);
        }
        String base = switch (descType) {
            case "Z" -> "boolean";
            case "B" -> "byte";
            case "C" -> "char";
            case "S" -> "short";
            case "I" -> "int";
            case "J" -> "long";
            case "F" -> "float";
            case "D" -> "double";
            default -> VersionMap.simple(descType);
        };
        return base + dims;
    }

    /** A descriptor's parameter types: class names dotted ($ kept), primitives and arrays as written. */
    /** A descriptor's return type, dotted with $ for nested classes, or its primitive letter. */
    /** The argument counts of each call .name( on a line (a call whose parentheses close on a later line counts -1). */
    static Set<Integer> callArgCounts(String line, String name) {
        Set<Integer> counts = new HashSet<>();
        Matcher m = Pattern.compile("\\.\\s*" + Pattern.quote(name) + "\\s*\\(").matcher(line);
        while (m.find()) {
            int close = closingParen(line, m.end() - 1);
            if (close < 0) {
                counts.add(-1);
                continue;
            }
            String inside = line.substring(m.end(), close);
            counts.add(inside.isBlank() ? 0 : topLevelArguments(inside).size());
        }
        return counts;
    }

    static String returnType(String desc) {
        String r = desc.substring(desc.indexOf(')') + 1);
        return r.startsWith("L") ? r.substring(1, r.length() - 1).replace('/', '.') : r;
    }

    static List<String> paramTypes(String desc) {
        List<String> out = new ArrayList<>();
        int open = desc.indexOf('('), close = desc.indexOf(')');
        if (open < 0 || close < 0) {
            return out;
        }
        Matcher m = Pattern.compile("\\[*(?:L([^;]+);|[ZBCSIJFD])").matcher(desc.substring(open + 1, close));
        while (m.find()) {
            out.add(m.group(1) != null ? m.group().replaceFirst("L([^;]+);", "$1").replace('/', '.') : m.group());
        }
        return out;
    }

    /** The indices of before that keep after's types in order, when there is exactly one such choice and something is dropped. */
    static int[] uniqueEmbedding(List<String> before, List<String> after) {
        if (after.size() >= before.size()) {
            return null;
        }
        List<int[]> found = new ArrayList<>();
        embed(before, after, 0, 0, new int[after.size()], found);
        return found.size() == 1 ? found.get(0) : null;
    }

    static void embed(List<String> before, List<String> after, int i, int j, int[] picked, List<int[]> found) {
        if (found.size() > 1) {
            return;
        }
        if (j == after.size()) {
            found.add(picked.clone());
            return;
        }
        for (int at = i; at <= before.size() - (after.size() - j); at++) {
            // javac shows a method reference or lambda argument as its text, which fits any interface parameter.
            boolean functional = before.get(at).matches(".*(?:::|->|\\[\\.\\.\\.\\]).*") && !after.get(j).matches("(?:int|long|short|byte|char|boolean|float|double|String)");
            if (before.get(at).equals(after.get(j)) || functional) {
                picked[j] = at;
                embed(before, after, at + 1, j + 1, picked, found);
            }
        }
    }

    /** Registries by element type, as javac prints it, for ResourceKey.create(Registries.X, id). */
    static final Map<String, String> REGISTRY_OF = Map.ofEntries(
        Map.entry("Recipe<?>", "RECIPE"),
        Map.entry("Item", "ITEM"),
        Map.entry("Block", "BLOCK"),
        Map.entry("LootTable", "LOOT_TABLE"),
        Map.entry("Level", "DIMENSION"),
        Map.entry("Biome", "BIOME"),
        Map.entry("EntityType<?>", "ENTITY_TYPE"),
        Map.entry("Enchantment", "ENCHANTMENT"),
        Map.entry("DamageType", "DAMAGE_TYPE"),
        Map.entry("Structure", "STRUCTURE"),
        Map.entry("PlacedFeature", "PLACED_FEATURE"),
        Map.entry("ConfiguredFeature<?,?>", "CONFIGURED_FEATURE"),
        Map.entry("MobEffect", "MOB_EFFECT"),
        Map.entry("SoundEvent", "SOUND_EVENT"),
        Map.entry("Fluid", "FLUID")
    );

    /**
     * The value written for a type the game changed, when there is one way to get it: a ResourceLocation becomes the
     * key of that registry, a key gives its location, and a value where a built-in registry's getter now comes first
     * gets the registry before it (an argument only). Null when none applies.
     */
    static String convert(String value, String from, String to, boolean argument) {
        String plain = value.matches("[\\w$.]+(?:\\([^()]*\\))*(?:\\.[\\w$]+(?:\\([^()]*\\))*)*") ? value : "(" + value + ")";
        Matcher key = Pattern.compile("^ResourceKey<(.+)>$").matcher(to);
        if (from.equals("ResourceLocation") && key.find() && REGISTRY_OF.containsKey(key.group(1))) {
            return "net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries." + REGISTRY_OF.get(key.group(1)) + ", " + value + ")";
        }
        // An id written as text (1.21.2: EntityType.Builder.build(String) -> build(ResourceKey<EntityType<?>>)); a
        // location's own text goes back to the location.
        if (from.equals("String") && key.find(0) && REGISTRY_OF.containsKey(key.group(1))) {
            Matcher text = Pattern.compile("^([\\w$]+(?:\\(\\))?(?:\\.[\\w$]+(?:\\(\\))?)*)\\.toString\\(\\)$").matcher(value.strip());
            String location = text.find() ? text.group(1) : "net.minecraft.resources.ResourceLocation.parse(" + value + ")";
            return "net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries." + REGISTRY_OF.get(key.group(1)) + ", " + location + ")";
        }
        if (from.matches("ResourceKey(?:<.*>)?") && to.equals("ResourceLocation")) {
            return plain + ".location()";
        }
        Matcher getter = Pattern.compile("^HolderGetter<(.+)>$").matcher(to);
        if (argument && getter.find() && VersionMap.BUILT_IN_REGISTRY.containsKey(getter.group(1)) && !from.startsWith("HolderGetter")) {
            return "net.minecraft.core.registries.BuiltInRegistries." + VersionMap.BUILT_IN_REGISTRY.get(getter.group(1)) + ", " + value;
        }
        return null;
    }

    /**
     * The expression around offset at: an initializer, argument or returned value, bounded by the '(' ',' '=' or
     * "return" before it and the ',' ')' or ';' after it at the same depth. Null when there is no such bound.
     */
    static int[] valueAt(String text, int at) {
        int depth = 0, start = -1;
        for (int i = at - 1; i >= 0 && start < 0; i--) {
            char ch = text.charAt(i);
            if (ch == ')' || ch == ']' || ch == '}') {
                depth++;
            } else if (ch == '(' || ch == '[' || ch == '{') {
                if (depth == 0) {
                    start = ch == '(' ? i + 1 : -2;
                }
                depth--;
            } else if (depth == 0 && ch == ',') {
                start = i + 1;
            } else if (depth == 0 && ch == '=' && "=!<>+-*/%&|^".indexOf(text.charAt(i - 1)) < 0 && text.charAt(i + 1) != '=') {
                start = i + 1;
            } else if (depth == 0 && ch == ';') {
                start = -2;
            } else if (depth == 0 && text.startsWith("return", i) && (i == 0 || !Character.isJavaIdentifierPart(text.charAt(i - 1)))
                && !Character.isJavaIdentifierPart(text.charAt(i + 6))) {
                start = i + 6;
            }
        }
        if (start < 0) {
            return null;
        }
        depth = 0;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') {
                i = stringEnd(text, i);
            } else if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
            } else if (ch == ')' || ch == ']' || ch == '}') {
                if (depth == 0) {
                    return i > at ? new int[] { start, i } : null;
                }
                depth--;
            } else if (depth == 0 && (ch == ',' || ch == ';')) {
                return i > at ? new int[] { start, i } : null;
            }
        }
        return null;
    }

    static int closingParen(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') {
                i = stringEnd(text, i);
            } else if (ch == '(') {
                depth++;
            } else if (ch == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    static int stringEnd(String text, int quote) {
        for (int i = quote + 1; i < text.length(); i++) {
            if (text.charAt(i) == '\\') {
                i++;
            } else if (text.charAt(i) == '"') {
                return i;
            }
        }
        return text.length() - 1;
    }

    static List<String> topLevelArguments(String args) {
        List<String> parts = new ArrayList<>();
        if (args.isBlank()) {
            return parts;
        }
        int depth = 0, from = 0;
        for (int i = 0; i < args.length(); i++) {
            char ch = args.charAt(i);
            if (ch == '"') {
                i = stringEnd(args, i);
            } else if (ch == '(' || ch == '{' || ch == '[') {
                depth++;
            } else if (ch == ')' || ch == '}' || ch == ']') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                parts.add(args.substring(from, i));
                from = i + 1;
            }
        }
        parts.add(args.substring(from));
        return parts;
    }

    static boolean extendsGameClass(String text, String type) {
        if (type == null) {
            return false;
        }
        Matcher declared = Pattern.compile(
            "\\bclass\\s+" + Pattern.quote(type) + "\\b[^{;]*?\\bextends\\s+([\\w.]+)"
        ).matcher(text);
        if (!declared.find()) {
            return false;
        }
        String parent = declared.group(1);
        if (parent.startsWith("net.minecraft.") || parent.startsWith("com.mojang.")) {
            return true;
        }
        return Pattern.compile(
            "(?m)^import\\s+(?:net\\.minecraft|com\\.mojang)\\.[\\w.]*\\." + Pattern.quote(parent) + "\\s*;"
        ).matcher(text).find();
    }

    static void run(Path mapFile, Path log, Path repo, boolean dryRun, Path rulesFile) throws IOException {
        MapKnown k = MapKnown.load(mapFile);
        Map<Path, String> texts = new LinkedHashMap<>();
        int replacements = 0;
        Map<String, Integer> byKind = new TreeMap<>();

        // 1. Imports of moved/renamed classes, every Java file in the repo.
        List<Path> files = sourceFiles(repo);
        Pattern imp = Pattern.compile("^import\\s+(static\\s+)?([\\w.]+)\\s*;", Pattern.MULTILINE);
        Map<String, String> dotted = new HashMap<>();
        for (var e : k.classFqn.entrySet()) {
            dotted.put(e.getKey().replace('$', '.'), e.getValue().replace('$', '.'));
        }
        for (Path f : files) {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            Matcher m = imp.matcher(text);
            StringBuilder sb = new StringBuilder();
            List<String[]> simpleRenames = new ArrayList<>();
            int hits = 0;
            while (m.find()) {
                String fqn = m.group(2);
                String to = dotted.get(fqn);
                if (to == null && m.group(1) != null && fqn.contains(".")) {
                    String owner = fqn.substring(0, fqn.lastIndexOf('.'));
                    if (dotted.containsKey(owner)) {
                        to = dotted.get(owner) + fqn.substring(owner.length());
                    }
                }
                if (to == null) {
                    continue;
                }
                m.appendReplacement(
                    sb,
                    Matcher.quoteReplacement("import " + (m.group(1) == null ? "" : "static ") + to + ";")
                );
                hits++;
                if (m.group(1) == null && !VersionMap.simple(fqn).equals(VersionMap.simple(to))) {
                    simpleRenames.add(new String[] { VersionMap.simple(fqn), VersionMap.simple(to) });
                }
            }
            if (hits == 0) {
                continue;
            }
            m.appendTail(sb);
            String updated = sb.toString();
            for (String[] r : simpleRenames) {
                int at = updated.indexOf("\n", updated.lastIndexOf("\nimport "));
                String head = updated.substring(0, at), body = updated.substring(at);
                Matcher wm = Pattern.compile("\\b" + Pattern.quote(r[0]) + "\\b").matcher(body);
                int n = 0;
                StringBuilder bb = new StringBuilder();
                while (wm.find()) {
                    wm.appendReplacement(bb, Matcher.quoteReplacement(r[1]));
                    n++;
                }
                wm.appendTail(bb);
                updated = head + bb;
                hits += n;
            }
            texts.put(f, updated);
            replacements += hits;
            byKind.merge("class", hits, Integer::sum);
        }

        // 1b. On-demand imports (import a.b.*;) of a package whose classes moved: each moved class the file names gets
        // its own import, and the wildcard goes when javac says the package is gone (1.21.11's critereon -> criterion,
        // split again in 26.2 into triggers, predicates, ...).
        Set<String> gonePackages = new HashSet<>();
        for (VersionMap.Err e : VersionMap.readErrors(log)) {
            Matcher gone = Pattern.compile("^package ([\\w.]+) does not exist$").matcher(e.message());
            Path f = sourcePath(e, repo);
            if (gone.find() && f != null && e.source().matches("\\s*import\\s+[\\w.]+\\.\\*\\s*;\\s*")) {
                gonePackages.add(f + "|" + gone.group(1));
            }
        }
        Map<String, Map<String, String>> movedByPackage = new HashMap<>(); // old package -> old simple -> new fqn
        for (var e : k.classFqn.entrySet()) {
            String from = e.getKey(), to = e.getValue();
            if (from.contains("$") || !from.contains(".")) {
                continue;
            }
            String pkg = from.substring(0, from.lastIndexOf('.'));
            if (!to.startsWith(pkg + ".") || to.substring(pkg.length() + 1).contains(".")) {
                movedByPackage.computeIfAbsent(pkg, x -> new HashMap<>()).put(VersionMap.simple(from), to.replace('$', '.'));
            }
        }
        Pattern onDemand = Pattern.compile("(?m)^import\\s+([\\w.]+)\\.\\*\\s*;[ \\t]*\\r?\\n?");
        for (Path f : files) {
            String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
            Matcher m = onDemand.matcher(text);
            StringBuilder sb = new StringBuilder();
            int hits = 0;
            List<String[]> simpleRenames = new ArrayList<>();
            while (m.find()) {
                Map<String, String> moved = movedByPackage.get(m.group(1));
                if (moved == null) {
                    continue;
                }
                String body = text.substring(Math.max(text.lastIndexOf("\nimport "), 0));
                String nl = text.contains("\r\n") ? "\r\n" : "\n";
                Set<String> added = new TreeSet<>();
                for (var c : moved.entrySet()) {
                    boolean declared = Pattern.compile("\\b(?:class|interface|enum|record)\\s+" + Pattern.quote(c.getKey()) + "\\b").matcher(text).find();
                    boolean explicit = Pattern.compile("(?m)^import\\s+[\\w.]+\\." + Pattern.quote(c.getKey()) + "\\s*;").matcher(text).find();
                    // A class of the file's own package with that name wins over an on-demand import; an explicit one would not.
                    boolean ownPackage = f.getParent() != null && Files.exists(f.getParent().resolve(c.getKey() + ".java"));
                    if (declared || explicit || ownPackage || !Pattern.compile("\\b" + Pattern.quote(c.getKey()) + "\\b").matcher(body).find()) {
                        continue;
                    }
                    added.add("import " + c.getValue() + ";" + nl);
                    if (!VersionMap.simple(c.getValue()).equals(c.getKey())) {
                        simpleRenames.add(new String[] { c.getKey(), VersionMap.simple(c.getValue()) });
                    }
                }
                boolean keep = !gonePackages.contains(f + "|" + m.group(1));
                if (added.isEmpty() && keep) {
                    continue;
                }
                m.appendReplacement(sb, Matcher.quoteReplacement((keep ? m.group() : "") + String.join("", added)));
                hits += Math.max(1, added.size());
            }
            if (hits == 0) {
                continue;
            }
            m.appendTail(sb);
            String updated = sb.toString();
            for (String[] r : simpleRenames) {
                int at = updated.indexOf("\n", updated.lastIndexOf("\nimport "));
                updated = updated.substring(0, at) + updated.substring(at).replaceAll("\\b" + Pattern.quote(r[0]) + "\\b", Matcher.quoteReplacement(r[1]));
            }
            texts.put(f, updated);
            replacements += hits;
            byKind.merge("on-demand import", hits, Integer::sum);
        }

        // 1c. A class the game removed and replaced (its own code uses the replacement where it used the class): a file
        // that imports it and has an error naming it switches the import and its uses (1.21.2: DirectionProperty ->
        // EnumProperty). The import proves the name is the game's class, not one of the mod's own.
        if (!k.classRewrites.isEmpty()) {
            Map<Path, Set<String>> named = new LinkedHashMap<>();
            for (VersionMap.Err e : VersionMap.readErrors(log)) {
                Path f = sourcePath(e, repo);
                String sym = e.symbol() == null ? "" : e.symbol();
                Matcher cls = Pattern.compile("^class (\\w+)").matcher(sym);
                Matcher pkg = Pattern.compile("^package ([\\w.]+) does not exist").matcher(e.message());
                String simpleName = cls.find() ? cls.group(1) : pkg.find() ? pkg.group(1).substring(pkg.group(1).lastIndexOf('.') + 1) : null;
                if (f != null && simpleName != null) {
                    named.computeIfAbsent(f, x -> new LinkedHashSet<>()).add(simpleName);
                }
            }
            for (var entry : named.entrySet()) {
                Path f = entry.getKey();
                if (!Files.isRegularFile(f)) {
                    continue;
                }
                String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
                String updated = text;
                for (var rw : k.classRewrites.entrySet()) {
                    String old = rw.getKey().replace('$', '.'), oldSimple = old.substring(old.lastIndexOf('.') + 1);
                    if (!entry.getValue().contains(oldSimple)) {
                        continue;
                    }
                    Matcher imported = Pattern.compile("(?m)^import\\s+" + Pattern.quote(old) + "\\s*;").matcher(updated);
                    if (!imported.find()) {
                        continue;
                    }
                    String neu = rw.getValue();
                    String outer = neu.replaceAll("\\$.*", "");
                    String written = neu.substring(outer.lastIndexOf('.') + 1).replace('$', '.');
                    updated = updated.substring(0, imported.start()) + "import " + outer + ";" + updated.substring(imported.end());
                    int at = updated.indexOf('\n', updated.lastIndexOf("\nimport "));
                    updated = updated.substring(0, at) + updated.substring(at).replaceAll("(?<![\\w$.])" + Pattern.quote(oldSimple) + "(?![\\w$])", Matcher.quoteReplacement(written));
                }
                if (!updated.equals(text)) {
                    texts.put(f, updated);
                    replacements++;
                    byKind.merge("game class rewrite", 1, Integer::sum);
                }
            }
        }

        // 2. Renamed methods and fields at the reported line and column.
        for (VersionMap.Err e : VersionMap.readErrors(log)) {
            String sym = e.symbol() == null ? "" : e.symbol();
            if (!e.message().equals("cannot find symbol")) {
                continue;
            }
            String from, to;
            boolean method = sym.startsWith("method ");
            if (method) {
                from = sym.substring(7, sym.indexOf('('));
                to = MapKnown.pick(k.methodTo.get(from), e.location());
            } else if (sym.startsWith("variable ")) {
                from = sym.substring(9).trim();
                to = MapKnown.pick(k.fieldTo.get(from), e.location());
            } else if (sym.startsWith("class ")) {
                from = sym.substring(6).trim();
                to = null;
            } else {
                continue;
            }
            if (to == null && !method && from.matches("[A-Z]\\w*")) {
                // A nested class written through its outer class (Capabilities.ItemHandler): javac looks it up in the
                // outer one, and the map renamed it there (NeoForge 26.x: Capabilities$ItemHandler -> Capabilities$Item).
                String where = MapKnown.locationType(e.location());
                to = k.nestedRename(where, from);
                // Written through a subclass (Sound Physics: ClonedLevelChunk.TicksToSave, declared in ChunkAccess).
                for (String parent : where == null ? Set.<String>of() : VersionMap.SUPERS.getOrDefault(where, Set.of())) {
                    if (to == null) {
                        to = k.nestedRename(parent, from);
                    }
                }
            }
            Path f = sourcePath(e, repo);
            if (f == null || !Files.isRegularFile(f)) {
                continue;
            }
            String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
            if (to == null && method && extendsGameClass(declaring(text, MapKnown.locationType(e.location()), files), MapKnown.locationType(e.location()))) {
                // A mod's own subclass of a game class: the member is inherited, so a rename the game made the
                // same way on every owner is the one it means (getMaxScroll -> maxScrollAmount on a list widget).
                to = MapKnown.unanimous(k.methodTo.get(from));
            }
            if (to == null && !method && from.matches("[A-Z][A-Z0-9_]*")) {
                // An enum constant in a case label is written unqualified, so javac names the enclosing class, not the
                // enum (TorchMaster: case SPAWN_EGG on 1.21.2's EntitySpawnReason, where it is SPAWN_ITEM_USE).
                String[] all = text.split("\r?\n", -1);
                if (
                    e.line() >= 1 &&
                    e.line() <= all.length &&
                    Pattern.compile("\\bcase\\b[^:>]*\\b" + Pattern.quote(from) + "\\b").matcher(all[e.line() - 1]).find()
                ) to = MapKnown.unanimous(k.fieldTo.get(from));
            }
            if (to == null) {
                continue;
            }
            String nl = text.contains("\r\n") ? "\r\n" : "\n";
            String[] lines = text.split("\r?\n", -1);
            if (e.line() < 1 || e.line() > lines.length) {
                continue;
            }
            String line = lines[e.line() - 1];
            int col = e.caret() >= 0 && e.caret() < line.length() && line.startsWith(from, e.caret())
                ? e.caret()
                : -1;
            if (col < 0) {
                Matcher wm = Pattern.compile(
                    "\\b" + Pattern.quote(from) + "\\b" + (method ? "\\s*\\(" : "")
                ).matcher(line);
                if (!wm.find()) {
                    continue;
                }
                col = wm.start();
            }
            lines[e.line() - 1] = line.substring(0, col) + to + line.substring(col + from.length());
            texts.put(f, String.join(nl, lines));
            replacements++;
            byKind.merge(method ? "method" : "field", 1, Integer::sum);
        }
        // 2a'. A nested class the game moved out of its outer class (1.21.2: ArmorItem$Type -> equipment.ArmorType), written
        // through the outer one: every Outer.Nested in the file becomes the class it is now, imported.
        for (VersionMap.Err e : VersionMap.readErrors(log)) {
            String sym = e.symbol() == null ? "" : e.symbol();
            String where = MapKnown.locationType(e.location());
            if (!e.message().equals("cannot find symbol") || !sym.startsWith("class ") || where == null || e.location() == null || !e.location().startsWith("class ")) {
                continue;
            }
            String nested = sym.substring(6).trim();
            Set<String> targets = new HashSet<>();
            for (var c : k.classFqn.entrySet()) {
                String from = c.getKey(), to = c.getValue();
                if (from.endsWith("$" + nested) && VersionMap.simple(from.substring(0, from.length() - nested.length() - 1)).equals(where) && !to.contains("$")) {
                    targets.add(to);
                }
            }
            Path f = sourcePath(e, repo);
            if (targets.size() != 1 || f == null || !Files.isRegularFile(f)) {
                continue;
            }
            String target = targets.iterator().next();
            String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
            String moved = text.replaceAll("(?<![\\w$.])" + Pattern.quote(where) + "\\s*\\.\\s*" + Pattern.quote(nested) + "\\b", Matcher.quoteReplacement(VersionMap.simple(target)));
            if (moved.equals(text)) {
                continue;
            }
            texts.put(f, withImport(moved, target));
            replacements++;
            byKind.merge("nested class moved out", 1, Integer::sum);
        }

        // 2b. An @Override that no longer overrides: the game renamed the method it overrode, the same way on every owner.
        for (VersionMap.Err e : VersionMap.readErrors(log)) {
            if (!e.message().startsWith("method does not override or implement a method from a supertype")) {
                continue;
            }
            Path f = sourcePath(e, repo);
            if (f == null || !Files.isRegularFile(f)) {
                continue;
            }
            String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
            String nl = text.contains("\r\n") ? "\r\n" : "\n";
            String[] lines = text.split("\r?\n", -1);
            // Only in a class that extends a game class: the method it meant to override is the game's.
            String enclosing = null;
            for (int back = Math.min(lines.length, e.line()) - 1; back >= 0 && enclosing == null; back--) {
                Matcher type = Pattern.compile("\\bclass\\s+(\\w+)").matcher(lines[back]);
                if (type.find()) {
                    enclosing = type.group(1);
                }
            }
            Set<String> supers = enclosing == null ? null : VersionMap.SUPERS.get(enclosing);
            if (supers == null && !extendsGameClass(text, enclosing)) {
                continue;
            }
            for (int at = e.line() - 1; at >= 0 && at < Math.min(lines.length, e.line() + 3); at++) {
                Matcher declared = Pattern.compile(
                    "^(\\s*(?:(?:public|protected|private|static|final|synchronized)\\s+)*[\\w.<>\\[\\], ?]+\\s+)(\\w+)(\\s*\\()"
                ).matcher(lines[at]);
                if (!declared.find() || lines[at].trim().startsWith("@")) {
                    continue;
                }
                // Narrowed by the class's own supertypes (from --supers) when the owners renamed it differently.
                String to = supers == null
                    ? MapKnown.unanimous(k.methodTo.get(declared.group(2)))
                    : MapKnown.within(k.methodTo.get(declared.group(2)), supers);
                if (to != null) {
                    lines[at] = declared.group(1) + to + lines[at].substring(declared.end(2));
                    texts.put(f, String.join(nl, lines));
                    replacements++;
                    byKind.merge("override", 1, Integer::sum);
                }
                break;
            }
        }
        // 2b*. A class that no longer implements an abstract method of its supertype because the game renamed it and the
        // mod's method has no @Override for javac to point at (1.21.2: LevelHeightAccessor.getMinBuildHeight -> getMinY):
        // the one method the class declares under a name the map renames to the missing one on that supertype, with the
        // same number of parameters, takes the new name.
        Pattern notAbstract = Pattern.compile("^(\\w+) is not abstract and does not override abstract method (\\w+)\\(([^)]*)\\) in ([\\w.$]+)");
        for (VersionMap.Err e : VersionMap.readErrors(log)) {
            Matcher na = notAbstract.matcher(e.message());
            Path f = sourcePath(e, repo);
            if (!na.find() || f == null || !Files.isRegularFile(f)) {
                continue;
            }
            String missing = na.group(2), owner = VersionMap.simple(na.group(4));
            int arity = na.group(3).isBlank() ? 0 : na.group(3).split(",").length;
            String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
            List<int[]> found = new ArrayList<>();
            for (var entry : k.methodTo.entrySet()) {
                boolean renamesHere = false;
                for (String[] to : entry.getValue()) {
                    renamesHere |= to[1].equals(missing) && (to[0].equals(owner) || to[2].equals(owner));
                }
                if (!renamesHere || entry.getKey().equals(missing)) {
                    continue;
                }
                Matcher decl = Pattern.compile(
                    "(?m)^\\s*(?:(?:public|protected|private|static|final|synchronized|default)\\s+)*[\\w.<>\\[\\], ?]+\\s+(" + Pattern.quote(entry.getKey()) + ")\\s*\\(([^)]*)\\)"
                ).matcher(text);
                while (decl.find()) {
                    int params = decl.group(2).isBlank() ? 0 : topLevelArguments(decl.group(2)).size();
                    if (params == arity) {
                        found.add(new int[] { decl.start(1), decl.end(1) });
                    }
                }
            }
            if (found.size() != 1) {
                continue; // none, or more than one method could be meant
            }
            int[] at = found.get(0);
            texts.put(f, text.substring(0, at[0]) + missing + text.substring(at[1]));
            replacements++;
            byKind.merge("override", 1, Integer::sum);
        }

        // 2b''. An @Override of a method the game removed from the class's supertype, with no rename or new signature
        // (1.21.2: Recipe.getResultItem, canCraftInDimensions, getIngredients): nothing is left to override, so the
        // annotation goes and the method stays as the mod's own (its own callers keep working). The game no longer
        // calls it; that is reported by mmt's exposure check as an unhooked override, not hidden.
        for (VersionMap.Err e : VersionMap.readErrors(log)) {
            if (!e.message().startsWith("method does not override or implement a method from a supertype")) {
                continue;
            }
            Path f = sourcePath(e, repo);
            if (f == null || !Files.isRegularFile(f)) {
                continue;
            }
            String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
            String[] lines = text.split("\r?\n", -1);
            if (e.line() < 1 || e.line() > lines.length || !lines[e.line() - 1].contains("@Override")) {
                continue;
            }
            String enclosing = null;
            for (int back = e.line() - 1; back >= 0 && enclosing == null; back--) {
                Matcher type = Pattern.compile("\\b(?:class|enum|record)\\s+(\\w+)").matcher(lines[back]);
                if (type.find()) {
                    enclosing = type.group(1);
                }
            }
            Set<String> supers = enclosing == null ? null : VersionMap.SUPERS.get(enclosing);
            if (supers == null) {
                continue;
            }
            String method = null;
            for (int at = e.line() - 1; at < Math.min(lines.length, e.line() + 3) && method == null; at++) {
                Matcher declared = Pattern.compile("(\\w+)\\s*\\(").matcher(lines[at].replaceAll("@\\w+(\\([^)]*\\))?", ""));
                if (declared.find()) {
                    method = declared.group(1);
                }
            }
            boolean removed = false, moved = false;
            for (String s : supers) {
                removed |= k.removedByOwner.contains(s + "." + method);
                moved |= k.sigTransitions.containsKey(s + "." + method);
            }
            moved |= MapKnown.within(k.methodTo.get(method), supers) != null;
            if (!removed || moved) {
                continue;
            }
            lines[e.line() - 1] = lines[e.line() - 1].replaceFirst("@Override\\s*", "");
            if (lines[e.line() - 1].isBlank()) {
                // A line of its own: keep it empty, so the lines below keep their numbers for the steps after this one.
                lines[e.line() - 1] = "";
            }
            texts.put(f, String.join(text.contains("\r\n") ? "\r\n" : "\n", lines));
            replacements++;
            byKind.merge("unhooked override", 1, Integer::sum);
        }

        // 2b'. An @Override whose method the game gave new parameters with the same name and return type (1.21.2:
        // Mob.doHurtTarget(Entity) -> doHurtTarget(ServerLevel, Entity); Block.wasExploded(Level, ...) -> (ServerLevel,
        // ...)): the declaration takes the new parameter list (an added parameter gets a fresh name, a type that
        // changed in place keeps the mod's name) and super calls inside pass the same. Read from the class's own
        // supertypes (--supers); a return type that changed is left to the compiler.
        Map<Path, Set<String>> overrideImports = new LinkedHashMap<>();
        List<VersionMap.Err> overrideOrder = new ArrayList<>(VersionMap.readErrors(log));
        overrideOrder.sort(Comparator.comparing(VersionMap.Err::file).thenComparing(Comparator.comparingInt(VersionMap.Err::line).reversed()));
        for (VersionMap.Err e : overrideOrder) {
            if (!e.message().startsWith("method does not override or implement a method from a supertype")) {
                continue;
            }
            Path f = sourcePath(e, repo);
            if (f == null || !Files.isRegularFile(f)) {
                continue;
            }
            String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
            Set<String> needed = new LinkedHashSet<>();
            String rewritten = overrideSignature(text, e, k, needed);
            if (rewritten != null && !rewritten.equals(text)) {
                texts.put(f, rewritten);
                overrideImports.computeIfAbsent(f, x -> new LinkedHashSet<>()).addAll(needed);
                replacements++;
                byKind.merge("override signature", 1, Integer::sum);
            }
        }
        for (var entry : overrideImports.entrySet()) {
            String text = texts.get(entry.getKey());
            for (String fqn : entry.getValue()) {
                text = withImport(text, fqn);
            }
            texts.put(entry.getKey(), text);
        }

        // 2c. A call whose method only lost parameters (the game's new descriptor keeps some of the old parameter types,
        // in order, matched exactly one way): the arguments for the dropped parameters go, and a rename is applied.
        Pattern applied = Pattern.compile("^method (\\w+) in (?:class|interface) ([\\w.$]+)(?:<[^>]*>)? cannot be applied to given types");
        List<VersionMap.Err> bottomUp = new ArrayList<>(VersionMap.readErrors(log));
        // Last line first: a call that spanned lines and loses arguments must not shift the lines of the ones above it.
        bottomUp.sort(Comparator.comparing(VersionMap.Err::file).thenComparing(Comparator.comparingInt(VersionMap.Err::line).reversed()));
        for (VersionMap.Err e : bottomUp) {
            Matcher am = applied.matcher(e.message());
            if (!am.find()) {
                continue;
            }
            String name = am.group(1), owner = VersionMap.simple(am.group(2));
            Set<String> transitions = k.sigTransitions.get(owner + "." + name);
            Set<String> targets = k.sigTo.get(owner + "." + name);
            if (transitions == null || transitions.size() != 1 || targets == null || targets.size() != 1) {
                continue;
            }
            String[] t = transitions.iterator().next().split(" -> ");
            if (t.length != 2 || !t[0].startsWith("(") || !t[1].startsWith("(")) {
                continue;
            }
            List<String> before = paramTypes(t[0]), after = paramTypes(t[1]);
            before.replaceAll(type -> k.classFqn.getOrDefault(type, type));
            int[] kept = uniqueEmbedding(before, after);
            if (kept == null) {
                continue;
            }
            String target = targets.iterator().next();
            String newName = target.substring(0, target.indexOf('('));
            Path f = sourcePath(e, repo);
            if (f == null || !Files.isRegularFile(f)) {
                continue;
            }
            String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
            int lineStart = 0;
            for (int i = 1; i < e.line() && lineStart > -1; i++) {
                int next = text.indexOf('\n', lineStart);
                lineStart = next < 0 ? -1 : next + 1;
            }
            if (lineStart < 0) {
                continue; // the file changed under the log: the reported line is gone
            }
            int lineEnd = text.indexOf('\n', lineStart);
            lineEnd = lineEnd < 0 ? text.length() : lineEnd;
            Matcher callAt = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\(").matcher(text);
            // javac's caret column can be past the end of the line as it is now (tabs, an earlier rewrite).
            callAt.region(Math.min(lineEnd, lineStart + Math.max(0, e.caret())), lineEnd);
            if (!callAt.find()) {
                callAt.region(lineStart, lineEnd);
                if (!callAt.find()) {
                    continue;
                }
            }
            int open = callAt.end() - 1, close = closingParen(text, open);
            if (close < 0) {
                continue;
            }
            List<String> args = topLevelArguments(text.substring(open + 1, close));
            if (args.size() != before.size()) {
                continue;
            }
            List<String> keep = new ArrayList<>();
            for (int i : kept) {
                keep.add(args.get(i).strip());
            }
            text = text.substring(0, callAt.start()) + newName + "(" + String.join(", ", keep) + ")" + text.substring(close + 1);
            texts.put(f, text);
            replacements++;
            byKind.merge("dropped parameters", 1, Integer::sum);
        }

        // 2c*. Arguments javac itself says the one candidate no longer takes (required: int,int,boolean / found:
        // int,int,boolean,boolean), for a method or a constructor (1.21.2's TextureTarget(int, int, boolean)): when the
        // required types are the found ones with some left out (trailing ones, or one way only), those arguments go.
        Pattern single = Pattern.compile("^(?:method|constructor) (\\w+) in (?:class|interface|enum|record) [\\w.$]+(?:<[^>]*>)? cannot be applied to given types");
        List<VersionMap.Err> fewer = new ArrayList<>(VersionMap.readErrors(log));
        fewer.sort(Comparator.comparing(VersionMap.Err::file).thenComparing(Comparator.comparingInt(VersionMap.Err::line).reversed()));
        for (VersionMap.Err e : fewer) {
            Matcher sm = single.matcher(e.message());
            String[] rf = VersionMap.REQUIRED_FOUND.get(e.file() + ":" + e.line());
            Path f = sourcePath(e, repo);
            if (!sm.find() || rf == null || f == null || !Files.isRegularFile(f)) {
                continue;
            }
            // The one candidate takes nothing now (1.21.2: Item.getDescriptionId(ItemStack) -> getDescriptionId()):
            // every argument goes, but only arguments that are plain names, so nothing a call computed is lost.
            boolean none = rf[0].equals("no arguments");
            List<String> required = new ArrayList<>(), found = new ArrayList<>();
            for (String t : none ? List.<String>of() : topLevelArguments(rf[0].replace('<', '(').replace('>', ')'))) {
                required.add(t.strip().replaceAll("\\(.*", ""));
            }
            for (String t : topLevelArguments(rf[1].replace('<', '(').replace('>', ')'))) {
                found.add(t.strip().replaceAll("\\(.*", ""));
            }
            // Equal types leave a choice (int,int,boolean,boolean to int,int,boolean): trailing parameters are what
            // the game drops when the rest are kept in order, so a required list that starts the found one keeps those.
            int[] kept = found.size() > required.size() && found.subList(0, required.size()).equals(required)
                ? java.util.stream.IntStream.range(0, required.size()).toArray()
                : uniqueEmbedding(found, required);
            if (kept == null) {
                continue;
            }
            String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
            int lineStart = 0;
            for (int i = 1; i < e.line() && lineStart > -1; i++) {
                int next = text.indexOf('\n', lineStart);
                lineStart = next < 0 ? -1 : next + 1;
            }
            if (lineStart < 0) {
                continue;
            }
            int lineEnd = text.indexOf('\n', lineStart);
            lineEnd = lineEnd < 0 ? text.length() : lineEnd;
            Matcher callAt = Pattern.compile("(?<![\\w$])(?:" + Pattern.quote(sm.group(1)) + "|super|this)\\s*(?:<[^>()]*>)?\\s*\\(").matcher(text);
            callAt.region(Math.min(lineEnd, lineStart + Math.max(0, e.caret())), lineEnd);
            if (!callAt.find()) {
                callAt.region(lineStart, lineEnd);
                if (!callAt.find()) {
                    continue;
                }
            }
            int open = callAt.end() - 1, close = closingParen(text, open);
            if (close < 0) {
                continue;
            }
            List<String> args = topLevelArguments(text.substring(open + 1, close));
            if (args.size() != found.size() || none && !args.stream().allMatch(a -> a.strip().matches("(?:this\\.)?[A-Za-z_$][\\w$]*"))) {
                continue;
            }
            List<String> keep = new ArrayList<>();
            for (int i : kept) {
                keep.add(args.get(i));
            }
            // The arguments keep their layout, and lines the dropped ones took stay, so javac's line numbers hold.
            String keptText = String.join(",", keep).replaceFirst("^\\s+", "");
            String was = text.substring(open + 1, close);
            keptText += "\n".repeat(Math.max(0, (int) (was.chars().filter(c -> c == '\n').count() - keptText.chars().filter(c -> c == '\n').count())));
            text = text.substring(0, open + 1) + keptText + text.substring(close);
            texts.put(f, text);
            replacements++;
            byKind.merge("arguments javac no longer takes", 1, Integer::sum);
        }

        // 2c*''. Trailing arguments the game added that the enclosing method already has (1.20.2: renderBackground(context)
        // inside render(DrawContext context, int mouseX, int mouseY, float delta) takes (context, mouseX, mouseY, delta)):
        // javac's required list is the found one plus some types, and the enclosing method's other parameters are
        // exactly those types in that order. Anything less certain is left.
        for (VersionMap.Err e : VersionMap.readErrors(log)) {
            Matcher sm = single.matcher(e.message());
            String[] rf = VersionMap.REQUIRED_FOUND.get(e.file() + ":" + e.line());
            Path f = sourcePath(e, repo);
            if (!sm.find() || rf == null || f == null || !Files.isRegularFile(f) || rf[0].equals("no arguments")) {
                continue;
            }
            List<String> required = new ArrayList<>(), found = new ArrayList<>();
            for (String t : topLevelArguments(rf[0].replace('<', '(').replace('>', ')'))) {
                required.add(t.strip().replaceAll("\\(.*", ""));
            }
            if (!rf[1].equals("no arguments")) {
                for (String t : topLevelArguments(rf[1].replace('<', '(').replace('>', ')'))) {
                    found.add(t.strip().replaceAll("\\(.*", ""));
                }
            }
            if (required.size() <= found.size() || !required.subList(0, found.size()).equals(found)) {
                continue;
            }
            List<String> missing = required.subList(found.size(), required.size());
            String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
            int lineStart = 0;
            for (int i = 1; i < e.line() && lineStart > -1; i++) {
                int next = text.indexOf('\n', lineStart);
                lineStart = next < 0 ? -1 : next + 1;
            }
            if (lineStart < 0) {
                continue;
            }
            // The enclosing method: the last declaration header before the call.
            Matcher header = Pattern.compile("(?m)^[ \\t]*(?:@\\w+\\s+)*(?:(?:public|protected|private|static|final|synchronized)\\s+)*[\\w<>\\[\\]?, .]+\\s+\\w+\\s*\\(([^)]*)\\)\\s*(?:throws [\\w., ]+)?\\s*\\{").matcher(text);
            String params = null;
            while (header.find() && header.start() < lineStart) {
                params = header.group(1);
            }
            if (params == null) {
                continue;
            }
            List<String[]> declared = new ArrayList<>(); // {type, name}
            for (String p : topLevelArguments(params.replace('<', '(').replace('>', ')'))) {
                String[] parts = p.strip().replaceAll("\\(.*?\\)", "").replaceAll("^final\\s+", "").split("\\s+");
                if (parts.length >= 2) {
                    declared.add(new String[] { parts[parts.length - 2], parts[parts.length - 1] });
                }
            }
            int lineEnd = text.indexOf('\n', lineStart);
            lineEnd = lineEnd < 0 ? text.length() : lineEnd;
            Matcher callAt = Pattern.compile("(?<![\\w$])(?:" + Pattern.quote(sm.group(1)) + "|super|this)\\s*\\(").matcher(text);
            callAt.region(Math.min(lineEnd, lineStart + Math.max(0, e.caret())), lineEnd);
            if (!callAt.find()) {
                continue;
            }
            int open = callAt.end() - 1, close = closingParen(text, open);
            if (close < 0) {
                continue;
            }
            String inside = text.substring(open + 1, close);
            List<String> args = inside.isBlank() ? List.of() : topLevelArguments(inside);
            List<String> unused = new ArrayList<>();
            List<String> unusedTypes = new ArrayList<>();
            for (String[] d : declared) {
                if (args.stream().noneMatch(a -> a.strip().equals(d[1]))) {
                    unused.add(d[1]);
                    unusedTypes.add(d[0]);
                }
            }
            if (args.size() != found.size() || !unusedTypes.equals(missing)) {
                continue;
            }
            text = text.substring(0, close) + (args.isEmpty() ? "" : ", ") + String.join(", ", unused) + text.substring(close);
            texts.put(f, text);
            replacements++;
            byKind.merge("arguments from the enclosing method", 1, Integer::sum);
        }

        // 2c**. An argument the game added that has one value: every candidate javac names takes a HolderGetter of a
        // built-in registry at the same place, and the call passes none there (1.21.2: ShapedRecipeBuilder.shaped(
        // RecipeCategory, Item) -> shaped(HolderGetter<Item>, RecipeCategory, ItemLike)). The registry is passed.
        Pattern noSuitableFor = Pattern.compile("^no suitable (?:method|constructor) found for (\\w+)\\((.*)\\)$");
        List<VersionMap.Err> more = new ArrayList<>(VersionMap.readErrors(log));
        more.sort(Comparator.comparing(VersionMap.Err::file).thenComparing(Comparator.comparingInt(VersionMap.Err::line).reversed()));
        for (VersionMap.Err e : more) {
            Matcher ns = noSuitableFor.matcher(e.message());
            Matcher sm = single.matcher(e.message());
            String key = e.file() + ":" + e.line();
            List<String> lists = VersionMap.CANDIDATES.get(key);
            String name, foundText;
            if (ns.find() && lists != null) {
                name = ns.group(1);
                foundText = ns.group(2);
            } else if (sm.find() && VersionMap.REQUIRED_FOUND.containsKey(key)) {
                name = sm.group(1);
                foundText = VersionMap.REQUIRED_FOUND.get(key)[1];
                lists = List.of(VersionMap.REQUIRED_FOUND.get(key)[0]);
            } else {
                continue;
            }
            List<String> found = foundText.equals("no arguments") || foundText.isEmpty()
                ? List.of()
                : topLevelArguments(foundText.replace('<', '(').replace('>', ')'));
            int at = -1;
            String value = null;
            for (String params : lists) {
                List<String> p = topLevelArguments(params.replace('<', '(').replace('>', ')'));
                int here = -1;
                String v = null;
                for (int ak = 0; ak < p.size() && here < 0; ak++) {
                    Matcher hg = Pattern.compile("^(?:[\\w.]*\\.)?HolderGetter\\((.+)\\)$").matcher(p.get(ak).strip());
                    String element = hg.find() ? hg.group(1).replace('(', '<').replace(')', '>') : "";
                    if (VersionMap.BUILT_IN_REGISTRY.containsKey(element)) {
                        here = ak;
                        v = "net.minecraft.core.registries.BuiltInRegistries." + VersionMap.BUILT_IN_REGISTRY.get(element);
                    }
                }
                if (here < 0 || (at >= 0 && at != here) || (value != null && !value.equals(v))) {
                    at = -1;
                    break;
                }
                at = here;
                value = v;
            }
            if (at < 0 || value == null || at > found.size() || (at < found.size() && found.get(at).strip().startsWith("HolderGetter"))) {
                continue;
            }
            Path f = sourcePath(e, repo);
            if (f == null || !Files.isRegularFile(f)) {
                continue;
            }
            String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
            int lineStart = 0;
            for (int i = 1; i < e.line() && lineStart > -1; i++) {
                int next = text.indexOf('\n', lineStart);
                lineStart = next < 0 ? -1 : next + 1;
            }
            if (lineStart < 0) {
                continue;
            }
            int lineEnd = text.indexOf('\n', lineStart);
            lineEnd = lineEnd < 0 ? text.length() : lineEnd;
            Matcher callAt = Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "\\s*\\(").matcher(text);
            callAt.region(Math.min(lineEnd, lineStart + Math.max(0, e.caret())), lineEnd);
            if (!callAt.find()) {
                callAt.region(lineStart, lineEnd);
                if (!callAt.find()) {
                    continue;
                }
            }
            int open = callAt.end() - 1, close = closingParen(text, open);
            if (close < 0) {
                continue;
            }
            List<String> args = topLevelArguments(text.substring(open + 1, close));
            if (args.size() != found.size()) {
                continue;
            }
            // Inserted where the argument goes, the others untouched.
            int insert = open + 1;
            for (int ak = 0; ak < at; ak++) {
                insert += args.get(ak).length() + 1;
            }
            String piece = at == 0 ? value + (args.isEmpty() ? "" : ", ") : (at < args.size() ? " " + value + "," : ", " + value);
            if (at > 0 && at == args.size()) {
                insert = close;
            }
            text = text.substring(0, insert) + piece + text.substring(insert);
            texts.put(f, text);
            replacements++;
            byKind.merge("registry the game now asks for", 1, Integer::sum);
        }

        // 2c**'. An argument the game added and always passes the same value for, read from its own calls (GameDiff's
        // "argDefaults"; 1.21.2: GuiGraphics.blitSprite(RenderType::guiTextured, ...)): where javac names a call that
        // takes that many arguments and the call is one short, the value goes in at the game's place.
        Pattern takes = Pattern.compile("^(?:method|constructor) (\\w+) in (?:class|interface|enum|record) ([\\w.$]+)(?:<[^>]*>)? cannot be applied to given types");
        Pattern noneFits = Pattern.compile("^no suitable (?:method|constructor) found for (\\w+)\\((.*)\\)$");
        List<VersionMap.Err> defaults = new ArrayList<>(VersionMap.readErrors(log));
        defaults.sort(Comparator.comparing(VersionMap.Err::file).thenComparing(Comparator.comparingInt(VersionMap.Err::line).reversed()));
        for (VersionMap.Err e : defaults) {
            Matcher tm = takes.matcher(e.message()), nm = noneFits.matcher(e.message());
            String name, owner = null;
            int found;
            String key = e.file() + ":" + e.line();
            if (tm.find() && VersionMap.REQUIRED_FOUND.containsKey(key)) {
                name = tm.group(1);
                owner = VersionMap.simple(tm.group(2));
                String f = VersionMap.REQUIRED_FOUND.get(key)[1];
                found = f.equals("no arguments") ? 0 : topLevelArguments(f.replace('<', '(').replace('>', ')')).size();
            } else if (nm.find()) {
                name = nm.group(1);
                found = nm.group(2).isBlank() ? 0 : topLevelArguments(nm.group(2).replace('<', '(').replace('>', ')')).size();
            } else {
                continue;
            }
            String[] pick = null;
            for (String[] d : k.argDefaults.getOrDefault(name, List.of())) {
                // {owner simple, index, value, arity}
                if ((owner == null || d[0].equals(owner) || VersionMap.SUPERS.getOrDefault(owner, Set.of()).contains(d[0]))
                    && Integer.parseInt(d[3]) == found + 1) {
                    if (pick != null && !pick[2].equals(d[2])) {
                        pick = null;
                        break; // two values could be meant
                    }
                    pick = d;
                }
            }
            // An overload the game never calls (Jade's 9-argument blitSprite): when every overload the game does call
            // gained the same value at the same place, and javac lists one taking one argument more, it gained it too.
            if (pick == null) {
                Set<String> agreed = new HashSet<>();
                String[] any = null;
                for (String[] d : k.argDefaults.getOrDefault(name, List.of())) {
                    if (owner == null || d[0].equals(owner) || VersionMap.SUPERS.getOrDefault(owner, Set.of()).contains(d[0])) {
                        agreed.add(d[1] + "|" + d[2]);
                        any = d;
                    }
                }
                boolean longer = false;
                for (String c : VersionMap.CANDIDATES.getOrDefault(key, List.of())) {
                    longer |= (c.isBlank() ? 0 : topLevelArguments(c.replace('<', '(').replace('>', ')')).size()) == found + 1;
                }
                if (VersionMap.REQUIRED_FOUND.containsKey(key)) {
                    String r = VersionMap.REQUIRED_FOUND.get(key)[0];
                    longer |= !r.equals("no arguments") && topLevelArguments(r.replace('<', '(').replace('>', ')')).size() == found + 1;
                }
                if (agreed.size() == 1 && longer) {
                    pick = any;
                }
            }
            Path f = sourcePath(e, repo);
            if (pick == null || f == null || !Files.isRegularFile(f)) {
                continue;
            }
            String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
            int lineStart = 0;
            for (int i = 1; i < e.line() && lineStart > -1; i++) {
                int next = text.indexOf('\n', lineStart);
                lineStart = next < 0 ? -1 : next + 1;
            }
            if (lineStart < 0) {
                continue;
            }
            int lineEnd = text.indexOf('\n', lineStart);
            lineEnd = lineEnd < 0 ? text.length() : lineEnd;
            Matcher callAt = Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "\\s*\\(").matcher(text);
            callAt.region(Math.min(lineEnd, lineStart + Math.max(0, e.caret())), lineEnd);
            if (!callAt.find()) {
                callAt.region(lineStart, lineEnd);
                if (!callAt.find()) {
                    continue;
                }
            }
            int open = callAt.end() - 1, close = closingParen(text, open);
            if (close < 0) {
                continue;
            }
            String inside = text.substring(open + 1, close);
            List<String> args = inside.isBlank() ? List.of() : topLevelArguments(inside);
            int index = Integer.parseInt(pick[1]);
            if (args.size() != found || index > args.size()) {
                continue;
            }
            // Insert at the boundary before argument INDEX (or at the end), keeping every argument's own text.
            int at = open + 1;
            for (int i = 0; i < index; i++) {
                at += args.get(i).length() + 1;
            }
            String piece = index == 0 ? pick[2] + (args.isEmpty() ? "" : ", ") : (index == args.size() ? ", " + pick[2] : " " + pick[2] + ",");
            if (index == args.size()) {
                at = close;
            }
            text = text.substring(0, at) + piece + text.substring(at);
            texts.put(f, text);
            replacements++;
            byKind.merge("argument the game always passes", 1, Integer::sum);
        }

        // 2c***. A value whose type the game changed to one it converts to in one known way, where javac's caret is:
        // the initializer, argument or returned value (1.21.2 recipe ids: ResourceLocation -> ResourceKey<Recipe<?>>).
        // "no suitable method" counts when one candidate's mismatch is such a conversion.
        Pattern incompatible = Pattern.compile("^incompatible types: (.+) cannot be converted to (.+)$");
        List<VersionMap.Err> typedErrs = new ArrayList<>(VersionMap.readErrors(log));
        typedErrs.sort(Comparator.comparing(VersionMap.Err::file).thenComparing(Comparator.comparingInt(VersionMap.Err::line).reversed())
            .thenComparing(Comparator.comparingInt(VersionMap.Err::caret).reversed()));
        Set<String> convertedAt = new HashSet<>();
        for (VersionMap.Err e : typedErrs) {
            Matcher it = incompatible.matcher(e.message());
            String from, to;
            String[] one = VersionMap.CONVERSION_MISMATCH.get(e.file() + ":" + e.line());
            if (it.find()) {
                from = it.group(1).strip();
                to = it.group(2).strip();
                one = null;
            } else if (one != null) {
                from = one[0];
                to = one[1];
            } else {
                continue;
            }
            Path f = sourcePath(e, repo);
            if (f == null || !Files.isRegularFile(f) || e.caret() < 0 || !convertedAt.add(e.file() + ":" + e.line() + ":" + e.caret())) {
                continue;
            }
            String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
            int lineStart = 0;
            for (int i = 1; i < e.line() && lineStart > -1; i++) {
                int next = text.indexOf('\n', lineStart);
                lineStart = next < 0 ? -1 : next + 1;
            }
            int lineEnd = lineStart < 0 ? -1 : text.indexOf('\n', lineStart);
            if (lineStart < 0 || lineStart + e.caret() >= (lineEnd < 0 ? text.length() : lineEnd)) {
                continue;
            }
            int[] span = null;
            if (one == null) {
                span = valueAt(text, lineStart + e.caret());
            } else {
                // javac points at the call: the argument is found by its place.
                Matcher call = Pattern.compile("(?<![\\w$])" + Pattern.quote(one[2]) + "\\s*\\(").matcher(text);
                call.region(lineStart + e.caret(), lineEnd < 0 ? text.length() : lineEnd);
                int open = call.find() ? call.end() - 1 : -1;
                int close = open < 0 ? -1 : closingParen(text, open);
                if (close > 0) {
                    List<String> args = topLevelArguments(text.substring(open + 1, close));
                    int index = Integer.parseInt(one[3]);
                    int from0 = open + 1;
                    for (int ai = 0; ai < index && ai < args.size(); ai++) {
                        from0 += args.get(ai).length() + 1;
                    }
                    span = index < args.size() ? new int[] { from0, from0 + args.get(index).length() } : null;
                }
            }
            if (span == null || span[0] == span[1]) {
                continue;
            }
            String value = text.substring(span[0], span[1]).strip();
            int start = span[0] + text.substring(span[0], span[1]).indexOf(value);
            String converted = convert(value, from, to, text.charAt(span[0] - 1) == '(' || text.charAt(span[0] - 1) == ',');
            if (converted == null) {
                continue;
            }
            text = text.substring(0, start) + converted + text.substring(start + value.length());
            texts.put(f, text);
            replacements++;
            byKind.merge("value the game now types differently", 1, Integer::sum);
        }

        // 2c''. A class that lost its type parameters (1.21.2: PlayerModel<T> -> PlayerModel): javac names the type, and
        // the type arguments written after it on that line go.
        Pattern noParameters = Pattern.compile("^type (\\w+) does not take parameters");
        for (VersionMap.Err e : VersionMap.readErrors(log)) {
            Matcher np = noParameters.matcher(e.message());
            Path f = sourcePath(e, repo);
            if (!np.find() || f == null || !Files.isRegularFile(f)) {
                continue;
            }
            String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
            String[] lines = text.split("\r?\n", -1);
            if (e.line() < 1 || e.line() > lines.length) {
                continue;
            }
            String line = lines[e.line() - 1];
            Matcher type = Pattern.compile("(?<![\\w$])" + Pattern.quote(np.group(1)) + "\\s*<").matcher(line);
            int from = Math.max(0, Math.min(line.length(), e.caret()));
            if (!type.find(from) && !type.find(0)) {
                continue;
            }
            int open = type.end() - 1, depth = 0, close = -1;
            for (int i = open; i < line.length(); i++) {
                if (line.charAt(i) == '<') {
                    depth++;
                } else if (line.charAt(i) == '>' && --depth == 0) {
                    close = i;
                    break;
                }
            }
            if (close < 0) {
                continue;
            }
            int nameEnd = type.start() + np.group(1).length();
            lines[e.line() - 1] = line.substring(0, nameEnd) + line.substring(close + 1);
            texts.put(f, String.join(text.contains("\r\n") ? "\r\n" : "\n", lines));
            replacements++;
            byKind.merge("type arguments dropped", 1, Integer::sum);
        }

        // 2c'''. A class that gained type parameters, written with the old count in a declared type (1.21.2:
        // EntityRenderer<? super T> getRenderer(...) -> EntityRenderer<T, S>): each missing one is a wildcard. Not
        // after extends, implements or new, where a wildcard is not allowed and a transform owns the change.
        Pattern needs = Pattern.compile("^wrong number of type arguments; required (\\d+)");
        for (VersionMap.Err e : VersionMap.readErrors(log)) {
            Matcher nd = needs.matcher(e.message());
            Path f = sourcePath(e, repo);
            if (!nd.find() || f == null || !Files.isRegularFile(f)) {
                continue;
            }
            int required = Integer.parseInt(nd.group(1));
            String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
            String[] lines = text.split("\r?\n", -1);
            if (e.line() < 1 || e.line() > lines.length) {
                continue;
            }
            String line = lines[e.line() - 1];
            int caret = Math.max(0, Math.min(line.length() - 1, e.caret()));
            Matcher typed = Pattern.compile("(\\b[A-Z][\\w.]*)\\s*<").matcher(line);
            int open = -1;
            String before = "";
            while (typed.find()) {
                if (typed.start() <= caret + 1) {
                    open = typed.end() - 1;
                    before = line.substring(0, typed.start());
                }
            }
            if (open < 0 || Pattern.compile("\\b(extends|implements|new)\\s*$").matcher(before).find()) {
                continue;
            }
            int depth = 0, close = -1;
            for (int i = open; i < line.length(); i++) {
                if (line.charAt(i) == '<') {
                    depth++;
                } else if (line.charAt(i) == '>' && --depth == 0) {
                    close = i;
                    break;
                }
            }
            if (close < 0) {
                continue;
            }
            int have = topLevelArguments(line.substring(open + 1, close).replace('<', '(').replace('>', ')')).size();
            if (have >= required) {
                continue;
            }
            lines[e.line() - 1] = line.substring(0, close) + ", ?".repeat(required - have) + line.substring(close);
            texts.put(f, String.join(text.contains("\r\n") ? "\r\n" : "\n", lines));
            replacements++;
            byKind.merge("type arguments added", 1, Integer::sum);
        }

        // 2c'. One overload renamed while another keeps the name (1.21.2: Direction.getNearest(double,double,double) ->
        // getApproximateNearest, getNearest(int,int,int) stays): javac finds no suitable method; the overload whose
        // parameters are the argument types javac lists, on the class the call names, gives the new name.
        Pattern noSuitable = Pattern.compile("^no suitable method found for (\\w+)\\(([^)]*)\\)");
        for (VersionMap.Err e : VersionMap.readErrors(log)) {
            Matcher ns = noSuitable.matcher(e.message());
            if (!ns.find() || !k.renamedOverloads.containsKey(ns.group(1))) {
                continue;
            }
            Path f = sourcePath(e, repo);
            if (f == null || !Files.isRegularFile(f)) {
                continue;
            }
            String name = ns.group(1);
            List<String> argTypes = new ArrayList<>();
            for (String t : ns.group(2).split(",")) {
                if (!t.isBlank()) {
                    argTypes.add(t.trim().replaceAll("<.*", ""));
                }
            }
            String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
            String[] lines = text.split("\r?\n", -1);
            if (e.line() < 1 || e.line() > lines.length) {
                continue;
            }
            String line = lines[e.line() - 1];
            Matcher qualified = Pattern.compile("(\\w+)\\s*\\.\\s*" + Pattern.quote(name) + "\\s*\\(").matcher(line);
            Set<String> owners = new HashSet<>();
            while (qualified.find()) {
                owners.add(qualified.group(1));
                owners.addAll(VersionMap.SUPERS.getOrDefault(qualified.group(1), Set.of()));
            }
            Set<String> to = new HashSet<>();
            for (String[] overload : k.renamedOverloads.get(name)) {
                List<String> params = new ArrayList<>();
                for (String p : paramTypes(overload[1])) {
                    params.add(javaTypeName(p));
                }
                if ((owners.isEmpty() || owners.contains(overload[0])) && params.equals(argTypes)) {
                    to.add(overload[2]);
                }
            }
            if (to.size() != 1) {
                continue;
            }
            Matcher call = Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "(\\s*\\()").matcher(line);
            if (!call.find()) {
                continue;
            }
            lines[e.line() - 1] = line.substring(0, call.start()) + to.iterator().next() + line.substring(call.start() + name.length());
            texts.put(f, String.join(text.contains("\r\n") ? "\r\n" : "\n", lines));
            replacements++;
            byKind.merge("renamed overload", 1, Integer::sum);
        }

        // 2d. A call the game rewrote itself (GameDiff's call-site rewrites): the replacement its own code calls, with
        // the arguments matched by type, the receiver kept, dropped or passed as the first argument.
        Pattern missingMethod = Pattern.compile("^method (\\w+)\\(");
        Map<Path, Set<String>> rewriteImports = new LinkedHashMap<>();
        List<VersionMap.Err> rewriteOrder = new ArrayList<>(VersionMap.readErrors(log));
        rewriteOrder.sort(Comparator.comparing(VersionMap.Err::file).thenComparing(Comparator.comparingInt(VersionMap.Err::line).reversed()));
        for (VersionMap.Err e : rewriteOrder) {
            String sym = e.symbol() == null ? "" : e.symbol();
            Matcher mm = missingMethod.matcher(sym);
            if (!e.message().equals("cannot find symbol") || !mm.find()) {
                continue;
            }
            String type = MapKnown.locationType(e.location());
            List<VersionMap.Rewrite> candidates = new ArrayList<>();
            Set<String> owners = new HashSet<>();
            if (type != null) {
                owners.add(type);
                owners.addAll(VersionMap.SUPERS.getOrDefault(type, Set.of()));
            }
            for (String owner : owners) {
                candidates.addAll(k.rewrites.getOrDefault(owner + "." + mm.group(1), List.of()));
            }
            Path f = sourcePath(e, repo);
            if (candidates.isEmpty() || f == null || !Files.isRegularFile(f)) {
                continue;
            }
            String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
            Set<String> needed = new LinkedHashSet<>();
            String rewritten = applyRewrite(text, e, mm.group(1), candidates, k, needed);
            if (rewritten != null && !rewritten.equals(text)) {
                texts.put(f, rewritten);
                rewriteImports.computeIfAbsent(f, x -> new LinkedHashSet<>()).addAll(needed);
                replacements++;
                byKind.merge("game rewrite", 1, Integer::sum);
            }
        }
        for (var entry : rewriteImports.entrySet()) {
            String text = texts.get(entry.getKey());
            for (String fqn : entry.getValue()) {
                text = withImport(text, fqn);
            }
            texts.put(entry.getKey(), text);
        }

        // 2d'. A name the game reused: 1.21.2 renamed Registry.get(id) to getValue and getHolder to get, so a mod's
        // registry.get(id) still compiles as a call and javac only objects where its result is used ("Optional<Reference
        // <T>> cannot be converted to T", or an inference whose lower bound is that). When the type javac found is what
        // the method now under that name returns, and the map renames the old one, the call on that line is renamed.
        Map<String, List<String[]>> reusedBy = new HashMap<>(); // new name -> {owner simple, old desc} of what took it
        for (var e : k.renamedOverloads.entrySet()) {
            for (String[] o : e.getValue()) {
                reusedBy.computeIfAbsent(o[2], x -> new ArrayList<>()).add(new String[] { o[0], o[1] });
            }
        }
        for (VersionMap.Err e : VersionMap.readErrors(log)) {
            Path f = sourcePath(e, repo);
            String foundType = VersionMap.FOUND_TYPE.get(e.file() + ":" + e.line());
            Matcher conv = Pattern.compile("^incompatible types: ([\\w.$]+)(?:<.*)? cannot be converted to ").matcher(e.message());
            if (foundType == null && conv.find()) {
                foundType = conv.group(1);
            }
            if (f == null || foundType == null || !Files.isRegularFile(f)) {
                continue;
            }
            String raw = foundType.replaceAll("<.*", "");
            raw = raw.substring(raw.lastIndexOf('.') + 1);
            String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
            if (e.line() - 1 >= lines.size()) {
                continue;
            }
            String line = lines.get(e.line() - 1);
            // A line a rule already rewrote this round (registry.get(id).map(Holder::value).orElse(null)) is not the one
            // javac read: it is left alone.
            if (e.source() != null && !e.source().isBlank() && !line.strip().equals(e.source().strip())) {
                continue;
            }
            String renamedTo = null, which = null;
            for (var reused : reusedBy.entrySet()) {
                String name = reused.getKey();
                if (!k.renamedOverloads.containsKey(name) || !Pattern.compile("\\.\\s*" + Pattern.quote(name) + "\\s*\\(").matcher(line).find()) {
                    continue;
                }
                for (String[] taker : reused.getValue()) {
                    String ret = VersionMap.simple(returnType(taker[1]).replace('$', '.'));
                    if (!ret.equals(raw)) {
                        continue;
                    }
                    for (String[] own : k.renamedOverloads.get(name)) {
                        if (own[0].equals(taker[0]) && callArgCounts(line, name).equals(Set.of(paramTypes(own[1]).size()))) {
                            if (renamedTo != null && !renamedTo.equals(own[2])) {
                                renamedTo = "";
                            }
                            else if (renamedTo == null) {
                                renamedTo = own[2];
                                which = name;
                            }
                        }
                    }
                }
            }
            if (renamedTo == null || renamedTo.isEmpty()) {
                continue;
            }
            Matcher calls = Pattern.compile("\\.(\\s*)" + Pattern.quote(which) + "(\\s*\\()").matcher(line);
            int count = 0;
            while (calls.find()) {
                count++;
            }
            if (count != 1) {
                continue;
            }
            lines.set(e.line() - 1, calls.replaceFirst(".$1" + Matcher.quoteReplacement(renamedTo) + "$2"));
            texts.put(f, String.join("\n", lines));
            replacements++;
            byKind.merge("reused names", 1, Integer::sum);
        }

        // 2e'. A method moved to a class the same holder keeps beside the old one (26.2: LevelRenderer.blockChanged ->
        // LevelExtractor, both fields of Minecraft): a call through holder.levelRenderer goes through holder.levelExtractor;
        // its arguments then follow the new signature like any other call.
        for (VersionMap.Err e : VersionMap.readErrors(log)) {
            String sym = e.symbol() == null ? "" : e.symbol();
            Matcher mm = Pattern.compile("^method (\\w+)\\(").matcher(sym);
            Matcher via = Pattern.compile("^variable (\\w+) of type ([\\w.$]+)").matcher(e.location() == null ? "" : e.location());
            if (!e.message().equals("cannot find symbol") || !mm.find() || !via.find()) {
                continue;
            }
            Set<String> moves = k.siblings.getOrDefault(VersionMap.simple(via.group(2)) + "." + mm.group(1), Set.of());
            String toField = null;
            for (String move : moves) {
                if (move.startsWith(via.group(1) + "|")) {
                    toField = toField == null ? move.substring(move.indexOf('|') + 1) : "";
                }
            }
            Path f = sourcePath(e, repo);
            if (toField == null || toField.isEmpty() || f == null || !Files.isRegularFile(f)) {
                continue;
            }
            String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
            String[] lines = text.split("\r?\n", -1);
            if (e.line() < 1 || e.line() > lines.length) {
                continue;
            }
            String moved = lines[e.line() - 1].replaceFirst(
                "\\.(\\s*)" + Pattern.quote(via.group(1)) + "(\\s*\\.\\s*" + Pattern.quote(mm.group(1)) + "\\s*\\()",
                ".$1" + Matcher.quoteReplacement(toField) + "$2"
            );
            if (moved.equals(lines[e.line() - 1])) {
                continue;
            }
            lines[e.line() - 1] = moved;
            texts.put(f, String.join(text.contains("\r\n") ? "\r\n" : "\n", lines));
            replacements++;
            byKind.merge("method moved beside its holder's field", 1, Integer::sum);
        }

        // 2e. A member the game replaced with a chain on the same receiver (GameDiff's "chains"): input.up becomes
        // input.keyPresses.forward(), a call keeps its arguments on the chain's last step. Not where it is assigned.
        Pattern missingMember = Pattern.compile("^(variable|method) (\\w+)");
        List<VersionMap.Err> chainOrder = new ArrayList<>(VersionMap.readErrors(log));
        chainOrder.sort(Comparator.comparing(VersionMap.Err::file).thenComparing(Comparator.comparingInt(VersionMap.Err::line).reversed()));
        for (VersionMap.Err e : chainOrder) {
            Matcher mm = missingMember.matcher(e.symbol() == null ? "" : e.symbol());
            String type = MapKnown.locationType(e.location());
            if (!e.message().equals("cannot find symbol") || !mm.find() || type == null) {
                continue;
            }
            boolean method = mm.group(1).equals("method");
            String[] chain = null;
            Set<String> owners = new LinkedHashSet<>();
            owners.add(type);
            owners.addAll(VersionMap.SUPERS.getOrDefault(type, Set.of()));
            for (String owner : owners) {
                for (String[] c : k.chains.getOrDefault(owner + "." + mm.group(2), List.of())) {
                    if (c[1].equals(method ? "m" : "f")) {
                        chain = c;
                    }
                }
            }
            Path f = sourcePath(e, repo);
            if (chain == null || f == null || !Files.isRegularFile(f)) {
                continue;
            }
            String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
            String rewritten = applyChain(text, e, mm.group(2), method, chain[0]);
            if (rewritten != null) {
                texts.put(f, rewritten);
                replacements++;
                byKind.merge("game chain", 1, Integer::sum);
            }
        }

        // 3. Learned rewrites (from other mods' real ports of this jump), on the reported lines only.
        if (rulesFile != null && Files.isRegularFile(rulesFile)) {
            Map<Path, Set<String>> pendingImports = new LinkedHashMap<>();
            List<String[]> learned = new ArrayList<>(); // from, to, imports
            Pattern importsField = Pattern.compile("\"imports\": \\[([^\\]]*)\\]");
            for (String line : Files.readAllLines(rulesFile, StandardCharsets.UTF_8)) {
                Matcher fm = Pattern.compile(
                    "\"from\": \"((?:[^\"\\\\]|\\\\.)*)\", \"to\": \"((?:[^\"\\\\]|\\\\.)*)\""
                ).matcher(line);
                if (!fm.find()) {
                    continue;
                }
                Matcher im = importsField.matcher(line);
                learned.add(
                    new String[] {
                        VersionMap.unq(fm.group(1)),
                        VersionMap.unq(fm.group(2)),
                        im.find() ? im.group(1).replace("\"", "") : "",
                    }
                );
            }
            Set<String> done = new HashSet<>();
            for (VersionMap.Err e : VersionMap.readErrors(log)) {
                Path f = sourcePath(e, repo);
                if (f == null || !Files.isRegularFile(f) || !done.add(f + ":" + e.line())) {
                    continue;
                }
                String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
                String nl = text.contains("\r\n") ? "\r\n" : "\n";
                List<String> lines = new ArrayList<>(Arrays.asList(text.split("\r?\n", -1)));
                if (e.line() < 1 || e.line() > lines.size()) {
                    continue;
                }
                String line = lines.get(e.line() - 1), before = line;
                Set<String> imports = new LinkedHashSet<>();
                // Whole names only, one rewrite per spot, and for "cannot find symbol" only rules about that symbol.
                String sym = e.symbol() == null
                    ? ""
                    : e.symbol().replaceFirst("^(method|variable|class) ", "").replaceAll("\\(.*", "").trim();
                List<String> usedFrom = new ArrayList<>();
                for (String[] r : learned) {
                    if (r[0].isEmpty() || usedFrom.stream().anyMatch(u -> u.contains(r[0]) || r[0].contains(u))) {
                        continue;
                    }
                    if (
                        !sym.isEmpty() &&
                        e.message().equals("cannot find symbol") &&
                        !MapLearn.tokens(r[0]).contains(sym)
                    ) {
                        continue;
                    }
                    String pat =
                        (Character.isLetterOrDigit(r[0].charAt(0)) ? "\\b" : "") +
                        Pattern.quote(r[0]) +
                        (Character.isLetterOrDigit(r[0].charAt(r[0].length() - 1)) ? "\\b" : "");
                    Matcher rm = Pattern.compile(pat).matcher(line);
                    if (!rm.find()) {
                        continue;
                    }
                    line = rm.replaceAll(Matcher.quoteReplacement(r[1]));
                    usedFrom.add(r[0]);
                    for (String im : r[2].split(",")) {
                        if (!im.isBlank()) {
                            imports.add(im.trim());
                        }
                    }
                }
                if (line.equals(before)) {
                    continue;
                }
                lines.set(e.line() - 1, line);
                pendingImports.computeIfAbsent(f, x -> new LinkedHashSet<>()).addAll(imports);
                texts.put(f, String.join(nl, lines));
                replacements++;
                byKind.merge("learned", 1, Integer::sum);
            }
            // Preserve javac's source line numbers until every reported line has been repaired.
            for (var entry : pendingImports.entrySet()) {
                String text = texts.get(entry.getKey());
                String nl = text.contains("\r\n") ? "\r\n" : "\n";
                List<String> lines = new ArrayList<>(Arrays.asList(text.split("\\r?\\n", -1)));
                int after = -1;
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).startsWith("import ") || lines.get(i).startsWith("package ")) {
                        after = i;
                    }
                }
                for (String im : entry.getValue()) {
                    if (!lines.contains("import " + im + ";")) {
                        lines.add(++after, "import " + im + ";");
                    }
                }
                texts.put(entry.getKey(), String.join(nl, lines));
            }
        }
        // API changes need the enclosing Java expression and local declarations, not a file-wide rename.
        Map<Path, List<VersionMap.Err>> apiErrors = new LinkedHashMap<>();
        for (VersionMap.Err e : VersionMap.readErrors(log)) {
            Path f = sourcePath(e, repo);
            if (f != null && Files.isRegularFile(f)) {
                apiErrors.computeIfAbsent(f, x -> new ArrayList<>()).add(e);
            }
        }
        for (var entry : apiErrors.entrySet()) {
            Path f = entry.getKey();
            String before = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
            String after = ApiFix.apply(k, f, before, entry.getValue());
            if (!after.equals(before)) {
                texts.put(f, after);
                replacements++;
                byKind.merge("api", 1, Integer::sum);
            }
        }
        if (!dryRun) {
            for (var e : texts.entrySet()) {
                Files.writeString(e.getKey(), e.getValue(), StandardCharsets.UTF_8);
            }
        }
        System.out.println(
            "map-fix: " +
            replacements +
            " replacement(s) in " +
            texts.size() +
            " file(s) " +
            byKind +
            (dryRun ? " (dry run)" : "")
        );
    }
}
