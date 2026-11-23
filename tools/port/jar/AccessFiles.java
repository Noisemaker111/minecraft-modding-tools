import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** A mod's access transformer and access widener: read, moved to the new names, and widened for what the port opens. */
final class AccessFiles {

    final JarPort port;

    AccessFiles(JarPort port) {
        this.port = port;
    }

    /** Every access transformer in a jar and its nested jars. */
    List<String> accessTransformers(Map<String, byte[]> entries, String name) throws IOException {
        List<String> out = new ArrayList<>();
        for (String file : atFiles(entries)) {
            byte[] at = entries.get(file);
            if (at != null) {
                out.add(new String(at, StandardCharsets.UTF_8));
            }
        }
        for (String n : port.bundledMods.loadedNested(entries, name)) {
            String inner = name + "!" + n.substring(n.lastIndexOf('/') + 1);
            out.addAll(accessTransformers(JarEntries.entries(entries.get(n), inner, new HashMap<>()), inner));
        }
        return out;
    }

    static final Pattern DECLARED_AT = Pattern.compile("\\[\\[accessTransformers\\]\\][^\\[]*?file\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)')");

    /**
     * The access transformers the loader reads from a jar: those its metadata lists (FML 1, NeoForge 20.2, already reads
     * [[accessTransformers]]), else the default one.
     */
    static List<String> atFiles(Map<String, byte[]> entries) {
        List<String> out = new ArrayList<>();
        byte[] toml = entries.getOrDefault(ModMetadata.NEW_TOML, entries.get(ModMetadata.OLD_TOML));
        if (toml != null) {
            Matcher m = DECLARED_AT.matcher(new String(toml, StandardCharsets.UTF_8));
            while (m.find()) {
                out.add((m.group(1) != null ? m.group(1) : m.group(2)).replaceFirst("^/", ""));
            }
        }
        if (out.isEmpty()) {
            out.add("META-INF/accesstransformer.cfg");
        }
        return out;
    }

    /**
     * Access widener lines name game members in the old release (Mojang's names, as ModNames reads them); each one is moved
     * to its new name, and a line whose class or member is gone is commented out, as in an access transformer.
     */
    String accessWidener(JarPort.Mod mod, String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\r?\n", -1)) {
            String body = line.replaceAll("#.*", "").trim();
            String[] p = body.split("\\s+");
            if (p.length >= 3 && (p[0].equals("accessWidener") || p[0].equals("classTweaker")) && p[2].equals("named") && JarPort.compareVersions(port.b.version, JarPort.UNOBFUSCATED) >= 0) {
                // From 26.1 the game runs in Mojang's names under Fabric's "official" namespace, and Fabric Loader refuses any other.
                out.append(p[0]).append(' ').append(p[1]).append(" official\n");
                continue;
            }
            if (p.length < 3 || p[0].equals("accessWidener") || p[0].equals("classTweaker")) {
                out.append(line).append('\n');
                continue;
            }
            String owner = p[2].replace('.', '/');
            String newOwner = port.cls(owner);
            String moved = null;
            boolean known = port.a.classes.containsKey(owner);
            if (!known) {
                moved = body; // another mod's class, or one the old release did not have: left as it is
            } else if (!port.b.classes.containsKey(newOwner)) {
                moved = null;
            } else if (p[1].equals("class")) {
                moved = p[0] + " class " + newOwner;
            } else if (p[1].equals("method") && p.length >= 5) {
                String[] m = port.route.method(owner, p[3], p[4]);
                GameDiff.Cls holder = Exposure.declaring(port.b, newOwner, 'm', m[1], m[2]);
                moved = holder == null ? null : p[0] + " method " + newOwner + " " + m[1] + " " + m[2];
            } else if (p[1].equals("field") && p.length >= 4) {
                String[] f = port.route.field(owner, p[3]);
                GameDiff.Cls holder = Exposure.declaring(port.b, newOwner, 'f', f[1], null);
                moved = holder == null ? null : p[0] + " field " + newOwner + " " + f[1] + " " + holder.fields.get(f[1]).desc;
            } else {
                moved = body;
            }
            if (moved == null) {
                out.append("# ").append(body).append("  # mmt port: gone in ").append(port.to).append('\n');
                mod.change("access widener line for a gone member commented out");
            } else {
                out.append(moved.equals(body) ? line : moved).append('\n');
            }
        }
        String s = out.toString();
        return s.endsWith("\n\n") ? s.substring(0, s.length() - 1) : s;
    }

    /** The port's own grants ("public a.b.C [member]") as access widener lines; a field's type comes from the new release. */
    String widenerLines(Set<String> grants) {
        StringBuilder out = new StringBuilder();
        for (String g : grants) {
            String[] p = g.split(" ");
            String owner = p[1].replace('.', '/');
            if (p.length == 2) {
                out.append("accessible class ").append(owner).append('\n');
            } else if (p[2].contains("(")) {
                int paren = p[2].indexOf('(');
                out.append("accessible method ").append(owner).append(' ').append(p[2], 0, paren).append(' ').append(p[2].substring(paren)).append('\n');
            } else {
                GameDiff.Cls holder = Exposure.declaring(port.b, owner, 'f', p[2], null);
                if (holder != null) {
                    out.append("accessible field ").append(owner).append(' ').append(p[2]).append(' ').append(holder.fields.get(p[2]).desc).append('\n');
                }
            }
        }
        return out.toString();
    }

    /**
     * What an access transformer makes public, in the new release's names, so linking does not count it as closed: a class
     * ("owner"), a member ("owner.name" or "owner.name+desc"), every field ("owner.*") or every method but constructors ("owner.*()").
     * What it makes protected (Fabric's extendable, written protected-f) is kept with a leading "~": a subclass may use it.
     */
    void widen(Set<String> grants, String text) {
        for (String line : text.split("\n")) {
            String[] p = line.replaceAll("#.*", "").trim().split("\\s+");
            boolean open = p[0].startsWith("public");
            if (p.length < 2 || !open && !p[0].startsWith("protected")) {
                continue;
            }
            String mark = open ? "" : "~";
            String owner = p[1].replace('.', '/');
            String newOwner = port.cls(owner);
            if (p.length == 2) {
                grants.add(mark + newOwner);
                continue;
            }
            int paren = p[2].indexOf('(');
            if (p[2].equals("*") || p[2].equals("*()")) {
                grants.add(mark + newOwner + "." + p[2]);
            } else if (paren < 0) {
                grants.add(mark + newOwner + "." + port.route.field(owner, p[2])[1]);
            } else {
                String[] moved = port.route.method(owner, p[2].substring(0, paren), p[2].substring(paren));
                grants.add(mark + newOwner + "." + moved[1] + moved[2]);
            }
        }
    }

    /**
     * Access transformer lines name game members in the old release; each one is moved to its new name. A line whose
     * class or member the new release no longer has opens nothing, so it is commented out: whatever in the mod used
     * that member is reported where it is used.
     */
    String accessTransformer(JarPort.Mod mod, String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String body = line.replaceAll("#.*", "").trim();
            String[] p = body.split("\\s+");
            if (p.length < 2) {
                out.append(line).append('\n');
                continue;
            }
            String owner = p[1].replace('.', '/');
            String rest = p.length > 2 ? p[2] : null;
            String newOwner = port.cls(owner);
            String newRest = rest;
            boolean gone = port.a.classes.containsKey(owner) && !port.b.classes.containsKey(newOwner);
            if (!gone && rest != null && !rest.startsWith("*") && port.a.classes.containsKey(owner)) {
                int paren = rest.indexOf('(');
                if (paren < 0) {
                    String[] moved = port.route.field(owner, rest);
                    gone = Exposure.declaring(port.b, newOwner, 'f', moved[1], null) == null;
                    newRest = moved[1];
                } else {
                    String name = rest.substring(0, paren), d = rest.substring(paren);
                    String[] moved = port.route.method(owner, name, d);
                    gone = Exposure.declaring(port.b, newOwner, 'm', moved[1], moved[2]) == null;
                    newRest = moved[1] + moved[2];
                }
            }
            if (gone) {
                out.append("# ").append(body).append("  # mmt port: gone in ").append(port.to).append('\n');
                mod.change("access transformer line for a gone member commented out");
                continue;
            }
            String moved = p[0] + " " + newOwner.replace('/', '.') + (newRest == null ? "" : " " + newRest);
            out.append(moved.equals(body) ? line : moved).append('\n');
        }
        return out.substring(0, out.length() - 1);
    }
}
