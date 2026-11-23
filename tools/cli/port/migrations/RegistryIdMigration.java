import java.util.*;
import java.util.regex.*;

/** Registry ids the game now takes as a ResourceKey. */
final class RegistryIdMigration {

    /**
     * 1.21.2 builds a block's and an item's description and loot from an id its Properties must carry before the
     * constructor runs; a mod that registers through its own helpers compiles and then fails at start-up ("Block id not
     * set"). Where a registration names what it makes, every Properties built in it, or passed to it by name, gets that
     * id: register(NAME, () -> new X(... Properties ...)) under the mod's id, and Registry.register(BuiltInRegistries.
     * BLOCK|ITEM, ID, new X(...)) under ID. A Properties that already sets an id is left alone.
     */
    static String registryIds(String text, String modId) {
        Matcher call = Pattern.compile("\\bregister\\s*\\(").matcher(text);
        List<int[]> calls = new ArrayList<>();
        while (call.find()) {
            int close = JavaText.closeParen(text, call.end() - 1);
            if (close > 0) calls.add(new int[] { call.end() - 1, close });
        }
        StringBuilder out = new StringBuilder(text);
        for (int c = calls.size() - 1; c >= 0; c--) {
            int open = calls.get(c)[0], close = calls.get(c)[1];
            List<int[]> args = JavaText.splitArgs(text, open + 1, close);
            String key = null, kind = null;
            int from, to;
            if (args.size() == 3) {
                Matcher reg = Pattern.compile("^\\s*(?:net\\.minecraft\\.core\\.registries\\.)?BuiltInRegistries\\.(BLOCK|ITEM)\\s*$")
                    .matcher(text.substring(args.get(0)[0], args.get(0)[1]));
                if (!reg.find()) continue;
                kind = reg.group(1);
                key = text.substring(args.get(1)[0], args.get(1)[1]).strip();
                from = args.get(2)[0];
                to = args.get(2)[1];
            } else if (args.size() == 2 && modId != null && text.substring(args.get(1)[0], args.get(1)[1]).strip().startsWith("()")) {
                String name = text.substring(args.get(0)[0], args.get(0)[1]).strip();
                boolean stringName = name.matches("\"[^\"]*\"") || name.endsWith(".getPath()")
                    || name.matches("[A-Za-z_$][\\w$]*") && Pattern.compile("\\bString\\s+" + Pattern.quote(name) + "\\b").matcher(text).find();
                if (!stringName) continue;
                key = "net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(\"" + modId + "\", " + name + ")";
                from = args.get(1)[0];
                to = args.get(1)[1];
            } else {
                continue;
            }
            // Inside the registration, last first so earlier offsets hold.
            List<int[]> inserts = new ArrayList<>(); // {offset, kind 0 block / 1 item, wrapEnd or -1}
            Matcher props = Pattern.compile(
                "(?:(?:BlockBehaviour|Block)\\.)?Properties\\.(?:of|ofFullCopy|ofLegacyCopy)\\s*\\(|new\\s+(?:Item\\.)?Properties\\s*\\(\\s*\\)"
            ).matcher(text);
            props.region(from, to);
            while (props.find()) {
                boolean item = props.group().startsWith("new");
                int end = item ? props.end() : JavaText.closeParen(text, props.end() - 1) + 1;
                end = JavaText.chainEnd(text, end, to);
                if (end > 0 && !text.substring(props.start(), end).contains(".setId(")) inserts.add(new int[] { end, item ? 1 : 0, -1 });
            }
            if (inserts.isEmpty()) {
                // A Properties the code passes by name (built before the registration, or a parameter).
                Matcher named = Pattern.compile("[(,]\\s*([a-z][\\w$]*)\\s*(?=[,)])").matcher(text);
                named.region(from, to);
                while (named.find()) {
                    String n = named.group(1);
                    Matcher decl = Pattern.compile(
                        "(?:\\b(Item|BlockBehaviour|Block)\\.Properties\\s+" + Pattern.quote(n) + "\\b|\\bvar\\s+" + Pattern.quote(n)
                            + "\\s*=\\s*(new\\s+Item\\.Properties|(?:BlockBehaviour|Block)\\.Properties\\.of))"
                    ).matcher(text);
                    if (!decl.find()) continue;
                    boolean item = "Item".equals(decl.group(1)) || decl.group(2) != null && decl.group(2).startsWith("new");
                    inserts.add(new int[] { named.end(1), item ? 1 : 0, -1 });
                }
            }
            for (int i = inserts.size() - 1; i >= 0; i--) {
                int[] at = inserts.get(i);
                String registry = at[1] == 1 ? "ITEM" : "BLOCK";
                if (kind != null && !kind.equals(registry)) continue;
                out.insert(at[0], ".setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries." + registry + ", " + key + "))");
            }
        }
        return out.toString();
    }
}
