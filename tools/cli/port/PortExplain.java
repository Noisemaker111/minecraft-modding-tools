import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** port --explain: each compiler error left after a port, named by the game change behind it. */
final class PortExplain {

    final Port port;

    PortExplain(Port port) {
        this.port = port;
    }

    /**
     * Every distinct compiler error left, each with the update that changed the name it uses when the
     * route's map history knows it. Nothing is dropped: an error the history cannot place still appears,
     * with changedIn empty and the reason.
     */
    List<Object> namedErrors(Path log, Map<String, Object> mapInfo) throws IOException {
        String text = Port.logText(log);
        Map<String, List<Map<String, Object>>> history = new HashMap<>();
        if (mapInfo != null) for (Object item : Json.array(mapInfo.get("history"))) {
            var event = Json.object(item);
            history.computeIfAbsent(JavaText.simpleName(String.valueOf(event.get("name"))), k -> new ArrayList<>()).add(event);
            Object to = event.get("to");
            if (to != null) {
                String last = String.valueOf(to).replaceAll("\\(.*", "");
                String name = JavaText.simpleName(
                    last.contains(".") && !"class".equals(event.get("kind"))
                        ? last.substring(last.lastIndexOf('.') + 1)
                        : last
                );
                if (!name.equals(JavaText.simpleName(String.valueOf(event.get("name"))))) history
                    .computeIfAbsent(name, k -> new ArrayList<>())
                    .add(event);
            }
        }
        List<Object> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher reported = Port.ERROR.matcher(text);
        List<int[]> spans = new ArrayList<>();
        List<String[]> heads = new ArrayList<>();
        while (reported.find()) {
            spans.add(new int[] { reported.start(), reported.end() });
            heads.add(new String[] { reported.group(1), reported.group(2), reported.group(3) });
        }
        for (int i = 0; i < spans.size(); i++) {
            String[] head = heads.get(i);
            if (!seen.add(head[0] + ":" + head[1] + ":" + head[2])) continue;
            String block = text.substring(
                spans.get(i)[1],
                i + 1 < spans.size() ? spans.get(i + 1)[0] : Math.min(text.length(), spans.get(i)[1] + 2000)
            );
            Map<String, Object> entry = Json.map(
                "file",
                head[0],
                "line",
                Integer.parseInt(head[1]),
                "message",
                head[2]
            );
            // Placing an error is a report, never a reason to lose it: a failed lookup keeps the error listed.
            if (port.route != null) try {
                explain(entry, head[2], block, history);
            } catch (RuntimeException | IOException failure) {
                entry.put("changedIn", List.of());
                entry.put("why", "Could not look this name up in the route history: " + failure.getMessage());
            }
            list.add(entry);
        }
        return list;
    }

    void explain(
        Map<String, Object> entry,
        String message,
        String block,
        Map<String, List<Map<String, Object>>> history
    ) throws IOException {
        String symbol = null, kind = null, owner = null;
        Matcher m = Pattern.compile(
            "symbol:\\s+(class|method|variable|constructor|static|interface|enum|record)\\s+(\\w+)"
        ).matcher(block);
        if (m.find()) {
            kind = m.group(1);
            symbol = m.group(2);
        }
        Matcher where = Pattern.compile(
            "location:\\s+(?:(?:class|interface|enum|record)\\s+([\\w.$]+)|variable \\w+ of type ([\\w.$]+))"
        ).matcher(block);
        if (where.find()) owner = JavaText.simpleName(
            (where.group(1) != null ? where.group(1) : where.group(2)).replaceAll("<.*", "")
        );
        Matcher applied = Pattern.compile(
            "(?:method|constructor) (\\w+) in (?:class|interface|enum|record) ([\\w.$]+)"
        ).matcher(message);
        Matcher suitable = Pattern.compile("no suitable (?:method|constructor) found for (\\w+)\\(").matcher(message);
        Matcher access = Pattern.compile(
            "(\\w+)(?:\\([^)]*\\))? has (?:private|protected) access in ([\\w.$]+)"
        ).matcher(message);
        Matcher pkg = Pattern.compile("package ([\\w.]+) does not exist").matcher(message);
        if (symbol == null && applied.find()) {
            kind = "method";
            symbol = applied.group(1);
            owner = JavaText.simpleName(applied.group(2).replaceAll("<.*", ""));
        } else if (symbol == null && suitable.find()) {
            kind = "method";
            symbol = suitable.group(1);
        } else if (symbol == null && access.find()) {
            kind = "member";
            symbol = access.group(1);
            owner = JavaText.simpleName(access.group(2));
        } else if (symbol == null && pkg.find()) {
            Matcher imported = Pattern.compile(
                "(?:import\\s+(?:static\\s+)?)?" + Pattern.quote(pkg.group(1)) + "\\.(\\w+)"
            ).matcher(block);
            kind = "class";
            symbol = imported.find() ? imported.group(1) : JavaText.simpleName(pkg.group(1));
        }
        // The source itself narrows a name: an import gives a class its package, and an @Override that no
        // longer overrides names the method and the supertype it was declared in.
        List<String> lines = List.of(), imports = new ArrayList<>();
        Path source = Path.of(String.valueOf(entry.get("file")));
        if (Files.isRegularFile(source)) {
            lines = Files.readAllLines(source);
            Matcher imp = Pattern.compile("(?m)^import\\s+([\\w.]+)\\s*;").matcher(String.join("\n", lines));
            while (imp.find()) imports.add(imp.group(1));
        }
        if (symbol == null && message.startsWith("method does not override") && !lines.isEmpty()) {
            int at = (Integer) entry.get("line") - 1;
            String around = String.join("\n", lines.subList(Math.max(0, at), Math.min(lines.size(), at + 4)));
            Matcher declared = Pattern.compile("\\b(\\w+)\\s*\\(").matcher(
                around.replaceAll("@\\w+(\\([^)]*\\))?", "")
            );
            Matcher parent = Pattern.compile("\\b(?:extends|implements)\\s+([\\w.]+)").matcher(
                String.join("\n", lines)
            );
            if (declared.find()) {
                kind = "method";
                symbol = declared.group(1);
                owner = parent.find() ? JavaText.simpleName(parent.group(1)) : null;
            }
        }
        Matcher extendsClause = Pattern.compile("\\bextends\\s+([\\w.]+)").matcher(String.join("\n", lines));
        String parent = extendsClause.find() ? JavaText.simpleName(extendsClause.group(1)) : null;
        String importedAs = null;
        if (symbol != null) for (String name : imports) if (name.endsWith("." + symbol)) importedAs = name;
        if (symbol != null) {
            entry.put("symbol", symbol);
            if (owner != null) entry.put("owner", owner);
        }
        List<Object> changed = new ArrayList<>();
        if (symbol != null) {
            List<Map<String, Object>> candidates = new ArrayList<>(), exact = new ArrayList<>();
            for (var event : history.getOrDefault(symbol, List.of())) {
                String eventKind = String.valueOf(event.get("kind"));
                boolean fits =
                    switch (kind == null ? "" : kind) {
                        case "class", "interface", "enum", "record" -> eventKind.equals("class");
                        case "method", "constructor" -> eventKind.equals("method");
                        case "variable" -> !eventKind.equals("method"); // a field, or a class used as a qualifier
                        default -> true; // a static import, an access error, or no symbol kind
                    };
                if (!fits) continue;
                candidates.add(event);
                String eventOwner = event.get("owner") == null ? null : JavaText.simpleName(String.valueOf(event.get("owner")));
                String wasOwner = event.get("was") == null
                    ? null
                    : JavaText.simpleName(String.valueOf(event.get("was")).replaceAll("\\(.*", "").replaceAll("\\.\\w+$", ""));
                if (owner != null && (owner.equals(eventOwner) || owner.equals(wasOwner))) exact.add(event);
                else if (
                    importedAs != null &&
                    eventKind.equals("class") &&
                    importedAs.equals(String.valueOf(event.get("name")).replace('$', '.'))
                ) exact.add(event);
                // Inherited from the class this file extends: its members, or a nested class used unqualified.
                else if (
                    importedAs == null &&
                    parent != null &&
                    (eventKind.equals("class")
                            ? String.valueOf(event.get("name")).endsWith("." + parent + "$" + symbol)
                            : parent.equals(eventOwner))
                ) exact.add(event);
            }
            // An imported class names its package; another class with the same simple name is not it.
            if (importedAs != null && exact.isEmpty()) candidates.removeIf(event -> "class".equals(event.get("kind")));
            List<String> decisive = List.of("removed", "signature", "moved", "renamed");
            exact.sort(Comparator.comparingInt(event -> decisive.indexOf(String.valueOf(event.get("what")))));
            // Without an owner match, a 26.x step file that names this owner beats a same-named member elsewhere.
            if (exact.isEmpty() && owner != null) changed.addAll(byName(symbol, owner));
            List<Map<String, Object>> pick = !exact.isEmpty() ? exact : changed.isEmpty() ? candidates : List.of();
            String match = !exact.isEmpty()
                ? "owner"
                : "name only: no change on " +
                (owner == null ? "a known owner" : owner) +
                "; the same name changed on another class, which may be a supertype";
            for (var event : pick.subList(0, Math.min(3, pick.size()))) {
                String step = String.valueOf(event.get("step"));
                Route.Hop hop = port.route.hopOf(step);
                String what = String.valueOf(event.get("what")), name = event.get("owner") == null
                    ? String.valueOf(event.get("name"))
                    : event.get("owner") + "." + event.get("name");
                String change =
                    event.get("kind") +
                    " " +
                    name +
                    " " +
                    what +
                    ("removed".equals(what) ? "" : " to " + event.get("to")) +
                    (event.get("was") != null &&
                            !String.valueOf(event.get("was")).startsWith(name) &&
                            !"signature".equals(what)
                            ? " (then named " + event.get("was") + ")"
                            : "");
                changed.add(
                    Json.map(
                        "step",
                        step,
                        "hop",
                        hop == null ? null : hop.from() + " -> " + hop.to(),
                        "change",
                        change,
                        "linked",
                        port.route.linkOf(step),
                        "match",
                        match
                    )
                );
            }
            if (exact.isEmpty() && pick == candidates && candidates.size() > 3) entry.put(
                "ambiguous",
                candidates.size() + " changes share this name; the first three are listed"
            );
            if (changed.isEmpty() && owner == null) changed.addAll(byName(symbol, null));
        }
        entry.put("changedIn", changed);
        if (changed.isEmpty()) entry.put(
            "why",
            symbol == null
                ? "The compiler names no symbol here; this is a type or behaviour change the map cannot place on one update."
                : "No update on this route renamed, moved or removed " +
                symbol +
                " in the mapping history; it is an API change the compiler reports here (or a name from a mod dependency)."
        );
    }

    /** Updates into 26.x have no shared ids; look the name up in the change index's own step files when present. */
    List<Object> byName(String symbol, String owner) throws IOException {
        List<Object> found = new ArrayList<>();
        for (String step : port.route.steps()) {
            if (!"by-name".equals(port.route.linkOf(step))) continue;
            String[] p = step.split(" -> ");
            boolean forward = Versions.compare(p[0], p[1]) < 0;
            Path file = port.build.harness.resolve(
                "coverage/changes/" + (forward ? p[0] + "__" + p[1] : p[1] + "__" + p[0]) + ".json"
            );
            if (!Files.isRegularFile(file)) continue;
            var data = Json.read(file);
            String removed = forward ? "Removed" : "Added";
            for (Object c : listOf(data.get("classes" + removed))) if (
                JavaText.simpleName(String.valueOf(c)).equals(symbol)
            ) found.add(
                Json.map(
                    "step",
                    step,
                    "hop",
                    hopName(step),
                    "change",
                    "class " + c + " removed (matched by name)",
                    "linked",
                    "by-name",
                    "match",
                    "26.x step file, matched by name (no shared ids)"
                )
            );
            for (var e : Json.object(data.get("members")).entrySet()) {
                if (owner != null && !JavaText.simpleName(e.getKey()).equals(owner)) continue;
                var members = Json.object(e.getValue());
                for (String section : List.of(
                    "methods" + removed,
                    "fields" + removed,
                    "methodsChanged",
                    "fieldsChanged",
                    "methodsRenamed",
                    "fieldsRenamed"
                )) for (Object member : listOf(members.get(section))) {
                    String sig = String.valueOf(member).replaceAll("^(?:\\w+ )*", ""), side = forward
                        ? sig
                        : sig.contains(" -> ") ? sig.substring(sig.indexOf(" -> ") + 4) : sig;
                    if (side.startsWith(symbol + "(") || side.startsWith(symbol + ":")) found.add(
                        Json.map(
                            "step",
                            step,
                            "hop",
                            hopName(step),
                            "change",
                            e.getKey() +
                            "." +
                            member +
                            " (" +
                            section.replaceAll("^(methods|fields)", "").toLowerCase(Locale.ROOT) +
                            ", matched by name)",
                            "linked",
                            "by-name",
                            "match",
                            "26.x step file, matched by name (no shared ids)"
                        )
                    );
                }
            }
            if (found.size() >= 3) break;
        }
        return found.size() > 3 ? new ArrayList<>(found.subList(0, 3)) : found;
    }

    static List<Object> listOf(Object value) {
        return value instanceof List<?> ? Json.array(value) : List.of();
    }

    String hopName(String step) {
        Route.Hop hop = port.route.hopOf(step);
        return hop == null ? null : hop.from() + " -> " + hop.to();
    }

    /** One record per hop: its updates, what the game changed there (names the source's first release had), rules run and errors placed. */
    Map<String, Object> hopRecords(Map<String, Object> record, Map<String, Object> mapInfo, List<Object> named) {
        Map<String, Map<String, Integer>> changes = new HashMap<>();
        if (mapInfo != null) for (Object item : Json.array(mapInfo.get("history"))) {
            var event = Json.object(item);
            changes
                .computeIfAbsent(String.valueOf(event.get("step")), k -> new TreeMap<>())
                .merge(event.get("kind") + "s " + event.get("what"), 1, Integer::sum);
        }
        for (Object item : Json.array(record.get("hops"))) {
            var hop = Json.object(item);
            Set<String> steps = new LinkedHashSet<>();
            for (Object s : Json.array(hop.get("steps"))) {
                var step = Json.object(s);
                String id = String.valueOf(step.get("step"));
                steps.add(id);
                step.put("changes", changes.getOrDefault(id, Map.of()));
            }
            List<String> rules = new ArrayList<>();
            port.rulesApplied.forEach((id, covered) -> {
                for (String step : covered) if (steps.contains(step)) {
                    rules.add(id);
                    break;
                }
            });
            int errors = 0;
            for (Object e : named) {
                List<Object> changed = Json.array(Json.object(e).get("changedIn"));
                if (
                    !changed.isEmpty() && steps.contains(String.valueOf(Json.object(changed.get(0)).get("step")))
                ) errors++;
            }
            hop.put("rulesApplied", rules);
            hop.put("errorsPlaced", errors);
        }
        return record;
    }
}
