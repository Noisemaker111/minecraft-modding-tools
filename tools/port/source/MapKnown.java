import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** A generated map, indexed the way compiler errors ask about it. */
final class MapKnown {

    String fromVersion, toVersion, names;
    final Map<String, String> classFqn = new LinkedHashMap<>(); // old fqn ($ nested) -> new fqn
    final Map<String, Set<String>> classBySimple = new HashMap<>(), removedClassBySimple = new HashMap<>();
    final Map<String, Set<String>> packages = new HashMap<>();
    final Map<String, Set<String[]>> methodTo = new HashMap<>(), fieldTo = new HashMap<>(); // old name -> {owner simple, new name}
    /** Renamed methods by old name, with the descriptor of the overload that was renamed: {owner simple, old desc, new name}. */
    final Map<String, List<String[]>> renamedOverloads = new HashMap<>();
    final Map<String, Set<String>> sigTo = new HashMap<>();
    final Map<String, Set<String>> sigTransitions = new HashMap<>();
    final Set<String> methodGone = new HashSet<>(), fieldGone = new HashSet<>(), sigNames = new HashSet<>();
    final List<Map<String, String>> history = new ArrayList<>(); // kind, name, what, was, to, step, in map order
    /** Removed methods by owner (simple) and name: an override of one has nothing left to override. */
    final Set<String> removedByOwner = new HashSet<>();
    /** Call-site rewrites the game made (GameDiff's "rewrites"), by old owner (simple) and name. */
    final Map<String, List<VersionMap.Rewrite>> rewrites = new HashMap<>();
    /** Removed classes and the class the game's own code uses in their place (GameDiff's "classRewrites"), dotted, $ nested. */
    final Map<String, String> classRewrites = new HashMap<>();
    /**
     * Members the game replaced with a chain on the same receiver (GameDiff's "chains"), by owner (simple, old and new)
     * and name: {steps ("f:keyPresses,m:forward"), "f" for an old field or "m" for an old method}.
     */
    final Map<String, List<String[]>> chains = new HashMap<>();
    /** Parameters the game added with one value it always passes (GameDiff's "argDefaults"), by new method name:
     * {owner simple (new), index, value, new parameter count}. */
    final Map<String, List<String[]>> argDefaults = new HashMap<>();
    /** Methods moved to a class one holder keeps beside the old one (GameDiff's "siblings"): "fromField|toField" by
     * old owner (simple) and name. */
    final Map<String, Set<String>> siblings = new HashMap<>();
    final Map<String, List<Map<String, String>>> historyByName = new HashMap<>(); // kind + name -> events, so lookups skip the full scan

    static MapKnown load(Path mapFile) throws IOException {
        MapKnown k = new MapKnown();
        String json = Files.readString(mapFile, StandardCharsets.UTF_8);
        k.fromVersion = VersionMap.field(json, "from");
        k.toVersion = VersionMap.field(json, "to");
        k.names = VersionMap.field(json, "names");
        String section = null;
        Pattern sec = Pattern.compile("^  \"(\\w+)\": \\[");
        for (String line : Files.readString(mapFile, StandardCharsets.UTF_8).split("\n")) {
            Matcher sm = sec.matcher(line);
            if (sm.find()) {
                section = sm.group(1);
                continue;
            }
            if (section == null) {
                continue;
            }
            String t = line.trim();
            if (section.equals("history") && t.contains("\"step\":")) {
                Map<String, String> event = new HashMap<>();
                event.put("kind", VersionMap.field(t, "kind"));
                event.put("name", VersionMap.field(t, "name"));
                event.put("what", VersionMap.field(t, "what"));
                event.put("was", VersionMap.field(t, "was"));
                event.put("to", VersionMap.field(t, "to"));
                event.put("step", VersionMap.field(t, "step"));
                k.history.add(event);
                k.historyByName.computeIfAbsent(event.get("kind") + "\0" + event.get("name"), x -> new ArrayList<>()).add(event);
                continue;
            }
            if (section.equals("classesRemoved")) {
                if (t.startsWith("\"")) {
                    String c = t.replaceAll("[\",]", "");
                    k.removedClassBySimple.computeIfAbsent(VersionMap.simple(c), x -> new HashSet<>()).add(c);
                }
                continue;
            }
            if (!t.startsWith("{")) {
                continue;
            }
            String from = VersionMap.field(t, "from"), to = VersionMap.field(t, "to"), owner = VersionMap.field(t, "owner"), newOwner = VersionMap.field(
                t,
                "newOwner"
            );
            String ownerSimple = owner == null ? "" : VersionMap.simple(owner);
            switch (section) {
                case "classes" -> {
                    k.classFqn.put(from, to);
                    k.classBySimple.computeIfAbsent(VersionMap.simple(from), x -> new HashSet<>()).add(to);
                    String pkg = from.contains(".") ? from.substring(0, from.lastIndexOf('.')) : "";
                    k.packages.computeIfAbsent(pkg, x -> new HashSet<>()).add(to);
                }
                case "methods", "methodsMoved" -> {
                    if (!from.equals(to)) {
                        k.methodTo
                            .computeIfAbsent(from, x -> new HashSet<>())
                            .add(
                                new String[] { ownerSimple, to, newOwner == null ? ownerSimple : VersionMap.simple(newOwner) }
                            );
                        String oldDesc = VersionMap.field(t, "desc");
                        if (oldDesc != null) k.renamedOverloads
                            .computeIfAbsent(from, x -> new ArrayList<>())
                            .add(new String[] { ownerSimple, oldDesc, to });
                    }
                }
                case "signatures" -> {
                    k.sigTo
                        .computeIfAbsent(ownerSimple + "." + from, x -> new HashSet<>())
                        .add(to + VersionMap.field(t, "newDesc"));
                    k.sigTransitions
                        .computeIfAbsent(ownerSimple + "." + from, x -> new HashSet<>())
                        .add(VersionMap.field(t, "desc") + " -> " + VersionMap.field(t, "newDesc"));
                    k.sigNames.add(from);
                }
                case "methodsRemoved" -> {
                    k.methodGone.add(from);
                    k.removedByOwner.add(ownerSimple + "." + from);
                }
                case "fields" -> k.fieldTo
                    .computeIfAbsent(from, x -> new HashSet<>())
                    .add(new String[] { ownerSimple, to, ownerSimple });
                case "fieldsRemoved" -> k.fieldGone.add(from);
                case "classRewrites" -> k.classRewrites.put(from, to);
                case "argDefaults" -> {
                    String toOwner = VersionMap.field(t, "toOwner"), toName = VersionMap.field(t, "to");
                    k.argDefaults.computeIfAbsent(toName, x -> new ArrayList<>()).add(new String[] {
                        VersionMap.simple(toOwner), t.replaceAll(".*\"index\": (\\d+).*", "$1"), VersionMap.field(t, "value"), String.valueOf(MapFix.paramTypes(VersionMap.field(t, "newDesc")).size())
                    });
                }
                case "chains" -> {
                    String[] chain = { VersionMap.field(t, "steps"), t.contains("\"field\": true") ? "f" : "m" };
                    k.chains.computeIfAbsent(ownerSimple + "." + from, x -> new ArrayList<>()).add(chain);
                    if (newOwner != null && !VersionMap.simple(newOwner).equals(ownerSimple)) {
                        k.chains.computeIfAbsent(VersionMap.simple(newOwner) + "." + from, x -> new ArrayList<>()).add(chain);
                    }
                }
                case "siblings" -> k.siblings
                    .computeIfAbsent(ownerSimple + "." + from, x -> new HashSet<>())
                    .add(VersionMap.field(t, "fromField") + "|" + VersionMap.field(t, "toField"));
                case "rewrites" -> k.rewrites
                    .computeIfAbsent(ownerSimple + "." + from, x -> new ArrayList<>())
                    .add(
                        new VersionMap.Rewrite(
                            owner,
                            from,
                            VersionMap.field(t, "desc"),
                            t.contains("\"static\": true"),
                            VersionMap.field(t, "toOwner"),
                            to,
                            VersionMap.field(t, "newDesc"),
                            t.contains("\"newStatic\": true"),
                            t.contains("\"static\":")
                        )
                    );
                default -> {}
            }
        }
        return k;
    }

    /** The one new name for an old member name, narrowed by the type javac says it looked in. */
    static String pick(Set<String[]> candidates, String location) {
        return forType(candidates, locationType(location));
    }

    /**
     * The one new name the receiver's own type (or a supertype --supers names) gave this member, else null. A
     * rename on another class with the same member name says nothing about this receiver: 1.21.2 renamed one
     * class's getGameTime to gameTime, and Level.getGameTime stayed.
     */
    static String forType(Set<String[]> candidates, String type) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (type == null) {
            return null;
        }
        Set<String> names = new HashSet<>();
        for (String[] c : candidates) {
            if (c[0].equals(type) || c[2].equals(type)) {
                names.add(c[1]);
            }
        }
        if (names.isEmpty() && VersionMap.SUPERS.containsKey(type)) {
            // The receiver's static type inherits the member: LocalPlayer.getHungerManager is Player's (PlayerEntity in yarn).
            Set<String> supers = VersionMap.SUPERS.get(type);
            for (String[] c : candidates) {
                if (supers.contains(c[0]) || supers.contains(c[2])) {
                    names.add(c[1]);
                }
            }
        }
        return names.size() == 1 ? names.iterator().next() : null;
    }

    /** The new simple name of a class nested in outer (a simple name), when the map renamed it in place; else null. */
    String nestedRename(String outer, String nested) {
        if (outer == null) {
            return null;
        }
        Set<String> names = new HashSet<>();
        for (var e : classFqn.entrySet()) {
            String from = e.getKey(), to = e.getValue();
            if (!from.endsWith("$" + nested) || !VersionMap.simple(from.substring(0, from.length() - nested.length() - 1)).equals(outer)) {
                continue;
            }
            String toOuter = to.contains("$") ? to.substring(0, to.lastIndexOf('$')) : "";
            if (VersionMap.simple(toOuter).equals(outer)) {
                names.add(to.substring(to.lastIndexOf('$') + 1));
            }
        }
        return names.size() == 1 ? names.iterator().next() : null;
    }

    /** The one new name among the owners that are one of these supertypes (simple names), else null. */
    static String within(Set<String[]> candidates, Set<String> supers) {
        if (candidates == null) {
            return null;
        }
        Set<String> names = new HashSet<>();
        for (String[] c : candidates) {
            if (supers.contains(c[0]) || supers.contains(c[2])) {
                names.add(c[1]);
            }
        }
        return names.size() == 1 ? names.iterator().next() : null;
    }

    /** The new name when every owner that renamed this member chose the same one, else null. */
    static String unanimous(Set<String[]> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        Set<String> names = new HashSet<>();
        for (String[] c : candidates) {
            names.add(c[1]);
        }
        return names.size() == 1 ? names.iterator().next() : null;
    }

    static String locationType(String location) {
        if (location == null) {
            return null;
        }
        Matcher m = Pattern.compile("of type ([\\w.$]+)").matcher(location);
        String t = m.find() ? m.group(1) : location.replaceFirst("^(class|interface|enum|record) ", "");
        t = t.replaceAll("<.*", "").trim();
        return VersionMap.simple(t);
    }
}
