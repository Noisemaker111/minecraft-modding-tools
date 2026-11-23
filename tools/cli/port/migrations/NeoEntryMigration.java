import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** A mod's entry class and event subscribers moved to NeoForge's event buses. */
final class NeoEntryMigration {

    final Port port;

    NeoEntryMigration(Port port) {
        this.port = port;
    }

    /** Mixin configs the repair deleted after converting their last mixin: undo restores them from backup. */
    final List<String> repairedAway = new ArrayList<>();

    /** The Forge event subscriber that replaces one convertible @Mixin method. */
    static String subscriberMethod(String shape, String owner) {
        if (shape.equals("fov")) {
            return "    @SubscribeEvent\n" +
                "    public void onFov(FOVUpdateEvent event) {\n" +
                "        " + owner + ".frameUpdate();\n" +
                "        Minecraft client = Minecraft.getMinecraft();\n" +
                "        if (client.currentScreen != null) return;\n" +
                "        double fov = event.fov;\n" +
                "        double mul = " + owner + ".getCurrentFovMul();\n" +
                "        if (mul != 1.0) event.newfov = (float) (fov * mul);\n" +
                "    }\n";
        }
        if (shape.equals("overlay")) {
            return "    @SubscribeEvent\n" +
                "    public void onOverlay(RenderGameOverlayEvent.Post event) {\n" +
                "        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;\n" +
                "        " + owner + ".renderBars();\n" +
                "    }\n";
        }
        return "    @SubscribeEvent\n" +
            "    public void onMouse(MouseEvent event) {\n" +
            "        if (event.dwheel == 0) return;\n" +
            "        Minecraft mc = Minecraft.getMinecraft();\n" +
            "        if (mc == null || mc.currentScreen != null) return;\n" +
            "        if (" + owner + ".onWheel(event.dwheel)) event.setCanceled(true);\n" +
            "    }\n";
    }

    /** The matching close for the bracket at open, skipping strings, chars and comments; -1 when unbalanced. */
    static int matchForward(String text, int open) {
        int depth = 0;
        boolean line = false;
        boolean block = false;
        char quote = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (line) {
                if (c == '\n') line = false;
                continue;
            }
            if (block) {
                if (c == '*' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                    block = false;
                    i++;
                }
                continue;
            }
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                line = true;
                i++;
                continue;
            }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                block = true;
                i++;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                continue;
            }
            if (c == '(' || c == '{' || c == '[') depth++;
            if (c == ')' || c == '}' || c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** The [start, end) span of the single @Inject handler, or null when the shape is unfamiliar. */
    static int[] injectSpan(String text) {
        int at = text.indexOf("@Inject(");
        if (at < 0 || at != text.lastIndexOf("@Inject(")) return null;
        int line = text.lastIndexOf('\n', at) + 1;
        int annotationEnd = matchForward(text, text.indexOf('(', at));
        if (annotationEnd < 0) return null;
        int start = text.indexOf("private void ", annotationEnd);
        if (start < 0) return null;
        int body = text.indexOf('{', start);
        if (body < 0) return null;
        int end = matchForward(text, body);
        if (end < 0) return null;
        int stop = end + 1 < text.length() && text.charAt(end + 1) == '\n' ? end + 2 : end + 1;
        return new int[]{line, stop};
    }

    /** A convertible @Mixin file becomes a 1.7.10 Forge event subscriber; anything else returns unchanged. */
    static String neoSubscriberConvert(String text) {
        if (!text.contains("@Mixin(")) return text;
        String shape = "";
        if (text.contains("GameRenderer.class") && text.contains("getFov") && text.contains("CallbackInfoReturnable<Double>")) shape = "fov";
        if (text.contains("Gui.class") && text.contains("GuiGraphics") && text.contains("renderBars(")) shape = "overlay";
        if (text.contains("MouseHandler.class") && text.contains("onScroll") && text.contains("onWheel(")) shape = "scroll";
        if (shape.isEmpty()) return text;
        String call = shape.equals("fov") ? "frameUpdate" : shape.equals("overlay") ? "renderBars" : "onWheel";
        Matcher manager = Pattern.compile("([\\w.]+)\\." + call + "\\(").matcher(text);
        if (!manager.find()) return text;
        int[] span = injectSpan(text);
        if (span == null) return text;
        String out = text.substring(0, span[0]) + subscriberMethod(shape, manager.group(1)) + text.substring(span[1]);
        out = out.replaceFirst("(?m)^@Mixin\\(.*\\)\\n", "");
        out = out.replaceAll("(?m)^\\s*@Shadow[^\\n]*\\n", "");
        out = out.replaceAll("(?m)^import [\\w.]*spongepowered[\\w.]*;\\n?", "");
        if (shape.equals("fov")) out = out.replaceAll("(?m)^import net\\.minecraft\\.client\\.renderer\\.GameRenderer;\\n?", "");
        if (shape.equals("fov")) out = out.replaceAll("(?m)^import net\\.minecraft\\.client\\.Camera;\\n?", "");
        if (shape.equals("overlay")) out = out.replaceAll("(?m)^import net\\.minecraft\\.client\\.gui\\.Gui;\\n?", "");
        if (shape.equals("overlay")) out = out.replaceAll("(?m)^import net\\.minecraft\\.client\\.gui\\.GuiGraphics;\\n?", "");
        if (shape.equals("overlay")) out = out.replaceAll("(?m)^import net\\.minecraft\\.client\\.DeltaTracker;\\n?", "");
        if (shape.equals("scroll")) out = out.replaceAll("(?m)^import net\\.minecraft\\.client\\.MouseHandler;\\n?", "");
        out = JavaText.addImport(out, "cpw.mods.fml.common.eventhandler.SubscribeEvent");
        if (shape.equals("fov")) out = JavaText.addImport(out, "net.minecraftforge.client.event.FOVUpdateEvent");
        if (shape.equals("overlay")) out = JavaText.addImport(out, "net.minecraftforge.client.event.RenderGameOverlayEvent");
        if (shape.equals("scroll")) out = JavaText.addImport(out, "net.minecraftforge.client.event.MouseEvent");
        if (shape.equals("scroll")) out = JavaText.addImport(out, "net.minecraft.client.Minecraft");
        return out;
    }

    /** The 1.7.10 form of a GuiGraphics bar overlay: ScaledResolution plus Gui.drawRect. */
    static String barsModern(String text) {
        Matcher m = Pattern.compile("(?s)public static void renderBars\\(GuiGraphics \\w+\\)\\s*\\{\\n(.*?)\\n    \\}").matcher(text);
        if (!m.find()) return text;
        if (!m.group(1).contains(".guiWidth()") || !m.group(1).contains(".fill(")) return text;
        String modern = "    public static void renderBars() {\n" +
            "        if (currentBarsPct <= 0.0001f) return;\n" +
            "        Minecraft mc = Minecraft.getMinecraft();\n" +
            "        ScaledResolution res = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);\n" +
            "        int sw = res.getScaledWidth();\n" +
            "        int sh = res.getScaledHeight();\n" +
            "        int h = Math.round(sh * (currentBarsPct / 100f));\n" +
            "        if (h <= 0) return;\n" +
            "        Gui.drawRect(0, 0, sw, h, 0xFF000000);\n" +
            "        Gui.drawRect(0, sh - h, sw, sh, 0xFF000000);\n" +
            "    }\n";
        String out = m.replaceFirst(Matcher.quoteReplacement(modern));
        out = JavaText.addImport(out, "net.minecraft.client.Minecraft");
        out = JavaText.addImport(out, "net.minecraft.client.gui.Gui");
        out = JavaText.addImport(out, "net.minecraft.client.gui.ScaledResolution");
        return out;
    }

    /** The first project source whose text holds the needle, or null. */
    Path findSource(String needle) throws Exception {
        for (Path file : Port.sources(port.build.project)) {
            if (!file.toString().endsWith(".java") || !Files.isRegularFile(file)) continue;
            if (Files.readString(file).contains(needle)) return file;
        }
        return null;
    }

    /** Runs a "neo-entry" rule: convertible @Mixin files become subscribers and the @Mod entry wires them. */
    boolean applyNeoEntry(Map<String, Object> rule, String errors) throws Exception {
        Pattern match = Pattern.compile(rule.get("match").toString());
        boolean changed = false;
        List<String> subscribers = new ArrayList<>();
        String overlayOwner = "";
        for (Path file : port.rules.reportedLines(errors, match).keySet()) {
            String original = Files.readString(file);
            String text = PortRules.keepingLineEndings(original, NeoEntryMigration::neoSubscriberConvert);
            if (text.equals(original)) continue;
            Files.writeString(file, port.rules.deferImports(file, original, text));
            changed = true;
            Matcher name = Pattern.compile("(?m)^package ([\\w.]+);[\\s\\S]*?public class (\\w+)").matcher(text);
            if (name.find()) subscribers.add(name.group(1) + "." + name.group(2));
            if (text.contains(".renderBars();")) {
                Matcher owner = Pattern.compile("([\\w.]+)\\.renderBars\\(\\);").matcher(text);
                if (owner.find()) overlayOwner = owner.group(1);
            }
        }
        if (!overlayOwner.isEmpty()) {
            Path manager = null;
            String tail = overlayOwner.replace('.', '/') + ".java";
            for (Path file : Port.sources(port.build.project)) {
                if (!file.toString().endsWith(".java") || !Files.isRegularFile(file)) continue;
                if (file.toString().replace('\\', '/').endsWith("/" + tail)) {
                    manager = file;
                    break;
                }
            }
            if (manager != null) {
                String original = Files.readString(manager);
                String text = PortRules.keepingLineEndings(original, NeoEntryMigration::barsModern);
                if (!text.equals(original)) {
                    Files.writeString(manager, port.rules.deferImports(manager, original, text));
                    changed = true;
                }
            }
        }
        Path mod = findSource("@Mod(");
        if (mod != null && (!subscribers.isEmpty() || overlayOwner.isEmpty())) {
            String original = Files.readString(mod);
            String text = PortRules.keepingLineEndings(original, source -> wireModEntry(source, mod, subscribers));
            if (!text.equals(original)) {
                Files.writeString(mod, port.rules.deferImports(mod, original, text));
                changed = true;
            }
        }
        if (!subscribers.isEmpty() && forgetConvertedMixins(subscribers)) changed = true;
        return changed;
    }

    /**
     * A mixin class the port rewrote as an event subscriber is no longer a mixin: its entry dies in any
     * mixin config for its package, and the config file itself when nothing it lists is a mixin anymore.
     * Tracked, so undo restores the config.
     */
    boolean forgetConvertedMixins(List<String> subscribers) throws Exception {
        Path resources = port.build.project.root.resolve("src/main/resources");
        if (!Files.isDirectory(resources)) return false;
        Map<String, List<String>> byPackage = new LinkedHashMap<>();
        for (String subscriber : subscribers) {
            int dot = subscriber.lastIndexOf('.');
            if (dot < 0) continue;
            String pack = subscriber.substring(0, dot);
            if (!byPackage.containsKey(pack)) byPackage.put(pack, new ArrayList<>());
            byPackage.get(pack).add(subscriber.substring(dot + 1));
        }
        boolean changed = false;
        List<Path> configs = new ArrayList<>();
        try (var files = Files.list(resources)) {
            for (Path file : files.toList()) {
                if (file.getFileName().toString().endsWith(".mixins.json")) configs.add(file);
            }
        }
        for (Path config : configs) {
            String original = Files.readString(config);
            String text = original;
            for (Map.Entry<String, List<String>> entry : byPackage.entrySet()) {
                text = stripConvertedMixins(text, entry.getKey(), entry.getValue());
            }
            if (text.equals(original)) continue;
            port.journal.track(config);
            if (mixinsRemain(text)) Files.writeString(config, text);
            else {
                Files.delete(config);
                repairedAway.add(port.build.project.root.relativize(config).toString().replace('\\', '/'));
            }
            changed = true;
        }
        return changed;
    }

    /** Drops the converted classes from a mixin config of their package; other packages pass through. */
    static String stripConvertedMixins(String text, String mixinPackage, List<String> simpleNames) {
        Matcher pack = Pattern.compile("\"package\"\\s*:\\s*\"([\\w.]+)\"").matcher(text);
        if (!pack.find() || !pack.group(1).equals(mixinPackage)) return text;
        String out = text;
        for (String name : simpleNames) {
            String quoted = Pattern.quote(name);
            out = Pattern.compile("(?m)^\\s*\"" + quoted + "\"\\s*,\\s*$").matcher(out).replaceAll("");
            out = Pattern.compile("\"" + quoted + "\"\\s*,").matcher(out).replaceAll("");
            out = Pattern.compile(",?\\s*\"" + quoted + "\"").matcher(out).replaceAll("");
            out = out.replaceAll(",\\s*\\]", "]");
        }
        return out;
    }

    /** Whether a mixin config still lists any mixin class. */
    static boolean mixinsRemain(String text) {
        Matcher arrays = Pattern.compile("\"(client|server|mixins)\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(text);
        while (arrays.find()) {
            if (arrays.group(2).contains("\"")) return true;
        }
        return false;
    }

    /** The @Mod entry gains subscriber registrations, its client handler, and a config factory it can see. */
    String wireModEntry(String text, Path mod, List<String> subscribers) throws Exception {
        Matcher cls = Pattern.compile("public class (\\w+)").matcher(text);
        if (!cls.find()) return text;
        String client = cls.group(1) + "Client";
        String factory = "";
        for (Path file : Port.sources(port.build.project)) {
            if (!file.toString().endsWith(".java") || !Files.isRegularFile(file) || file.equals(mod)) continue;
            if (Files.readString(file).contains("implements IModGuiFactory")) {
                Matcher name = Pattern.compile("(?m)^package ([\\w.]+);[\\s\\S]*?public class (\\w+)").matcher(Files.readString(file));
                if (name.find()) factory = name.group(1) + "." + name.group(2);
            }
        }
        Matcher pre = Pattern.compile("(public void preInit\\(FMLPreInitializationEvent \\w+\\)\\s*\\{\\n)").matcher(text);
        if (!pre.find()) return text;
        StringBuilder lines = new StringBuilder();
        if (Files.isRegularFile(mod.getParent().resolve(client + ".java"))) lines.append("        new ").append(client).append("();\n");
        for (String subscriber : subscribers) {
            lines.append("        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new ").append(subscriber).append("());\n");
        }
        String out = pre.replaceFirst(Matcher.quoteReplacement(pre.group(1) + lines.toString()));
        if (!factory.isEmpty() && !out.contains("guiFactory")) {
            out = out.replaceFirst("@Mod\\(modid = ([^)]+?)\\)", "@Mod(modid = $1, guiFactory = \"" + factory + "\")");
        }
        return out;
    }
}
