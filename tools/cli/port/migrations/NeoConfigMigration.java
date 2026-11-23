import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Forge config specs moved to NeoForge's ModConfigSpec. */
final class NeoConfigMigration {

    final Port port;

    NeoConfigMigration(Port port) {
        this.port = port;
    }

    /** Runs a "neo-config" rule: a ModConfigSpec holder becomes plain Configuration-backed fields with a sync method. */
    boolean applyNeoConfig(Map<String, Object> rule, String errors) throws Exception {
        Pattern match = Pattern.compile(rule.get("match").toString());
        boolean changed = false;
        for (Path file : port.rules.reportedLines(errors, match).keySet()) {
            String original = Files.readString(file);
            String text = PortRules.keepingLineEndings(original, NeoConfigMigration::neoConfigConvert);
            if (!text.equals(original)) {
                Files.writeString(file, port.rules.deferImports(file, original, text));
                changed = true;
            }
        }
        return changed;
    }

    /** The ModConfigSpec value kinds the converter keeps: double, int, bool, or define for the inferred kind. */
    static String configKind(String type, String rest) {
        if (type.equals("DoubleValue")) return "double";
        if (type.equals("IntValue")) return "int";
        if (type.equals("BooleanValue")) return "bool";
        if (type.equals("ConfigValue") && rest.contains(".define(")) return defineKind(rest);
        return "";
    }

    /** A define default's kind from its literal: boolean, integer, quoted string, or double. */
    static String defineKind(String rest) {
        Matcher def = Pattern.compile("\\.define\\(\"\\w+\", ([^)]+?)\\)").matcher(rest);
        if (!def.find()) return "";
        String literal = def.group(1).strip();
        if (literal.equals("true") || literal.equals("false")) return "bool";
        if (literal.matches("\\d+")) return "int";
        if (literal.startsWith("\"") && literal.endsWith("\"")) return "string";
        return "double";
    }

    /** The field assignment for one converted entry, with a float suffix where a float field needs one. */
    static String configDefault(String kind, String def) {
        if (kind.equals("double") && def.matches("\\d+\\.\\d+")) return def + "f";
        return def;
    }

    /** The Configuration getter call that loads one entry, with its range only where defineInRange gave one. */
    static String configLoad(String kind, String name, String key, String def, String range, String comment) {
        String get = "config.get(Configuration.CATEGORY_GENERAL, \"" + key + "\", " + def + ", \"" + comment + "\"" + range + ")";
        if (kind.equals("bool")) return name + " = " + get + ".getBoolean();";
        if (kind.equals("int")) return name + " = " + get + ".getInt();";
        if (kind.equals("string")) return name + " = " + get + ".getString();";
        return name + " = (float) " + get + ".getDouble();";
    }

    /** One ModConfigSpec value line becomes a plain field, queuing its Configuration load; empty when unknown. */
    static String configField(String type, String name, String rest, List<String> loads) {
        String kind = configKind(type, rest);
        if (kind.isEmpty()) return "";
        Matcher range = Pattern.compile("\\.comment\\(\"((?:[^\"\\\\\\n]|\\\\.)*)\"\\)|\\.defineInRange\\(\"(\\w+)\", ([^,]+?), ([^,]+?), ([^)]+?)\\)|\\.define\\(\"(\\w+)\", ([^)]+?)\\)").matcher(rest);
        String comment = "", key = "", def = "", min = "", max = "";
        while (range.find()) {
            if (range.group(1) != null) comment = range.group(1).replace("\\", "\\\\").replace("\"", "\\\"");
            if (range.group(2) != null) {
                key = range.group(2);
                def = range.group(3).strip();
                min = range.group(4).strip();
                max = range.group(5).strip();
            }
            if (range.group(6) != null) {
                key = range.group(6);
                def = range.group(7).strip();
            }
        }
        if (key.isEmpty()) return "";
        boolean ranged = rest.contains(".defineInRange(");
        if (ranged && min.isEmpty()) return "";
        if (kind.equals("bool") && ranged) return "";
        loads.add(configLoad(kind, name, key, def, ranged ? ", " + min + ", " + max : "", comment));
        String field = kind.equals("double") ? "float" : kind.equals("int") ? "int" : kind.equals("bool") ? "boolean" : "String";
        return "public static " + field + " " + name + " = " + configDefault(kind, def) + ";";
    }

    /** A ModConfigSpec holder with only understood entries becomes 1.7.10 Configuration fields plus sync; else unchanged. */
    static String neoConfigConvert(String text) {
        if (!text.contains("ModConfigSpec")) return text;
        List<String> loads = new ArrayList<>();
        StringBuffer fields = new StringBuffer();
        Matcher decl = Pattern.compile("^(\\s*)public static final ModConfigSpec\\.(\\w+) (\\w+) = BUILDER(.*?\\)\\s*;)", Pattern.MULTILINE | Pattern.DOTALL).matcher(text);
        while (decl.find()) {
            String flat = decl.group(4).replaceAll("\\s+", " ");
            String entry = configField(decl.group(2), decl.group(3), flat, loads);
            if (entry.isEmpty()) return text;
            decl.appendReplacement(fields, Matcher.quoteReplacement(decl.group(1) + entry));
        }
        decl.appendTail(fields);
        String[] lines = fields.toString().split("\n", -1);
        List<String> out = new ArrayList<>();
        boolean spec = false;
        for (String line : lines) {
            if (line.matches("\\s*private static final ModConfigSpec\\.Builder BUILDER = .*")) continue;
            if (line.contains("ModConfigSpec SPEC = BUILDER.build()")) {
                String indent = line.substring(0, line.length() - line.stripLeading().length());
                out.add(indent + "public static void sync(Configuration config) {");
                for (String load : loads) out.add(indent + "    " + load);
                out.add(indent + "    if (config.hasChanged()) config.save();");
                out.add(indent + "}");
                spec = true;
                continue;
            }
            out.add(line);
        }
        if (!spec || loads.isEmpty()) return text;
        String joined = String.join("\n", out);
        joined = joined.replaceFirst("(?m)^import net\\.neoforged\\.neoforge\\.common\\.ModConfigSpec;\\n?", "");
        return JavaText.addImport(joined, "net.minecraftforge.common.config.Configuration");
    }
}
