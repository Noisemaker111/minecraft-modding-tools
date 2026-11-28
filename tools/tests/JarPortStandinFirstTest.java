import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * A port back where the route links a member to one that does not mean the same, and a hook now handed a plain value.
 * Release 2.0's Sink.add(float) ends the entry it writes, as 1.21's VertexConsumer.addVertex ends the vertex before it;
 * release 1.0's Sink.put(float) writes and leaves the entry open until end(), as 1.20.6's vertex and endVertex. The
 * change map routes add to put (the game's own calls moved from one to the other), which links but drops the end. A
 * stand-in written for add (knowledge/standins/2.0-1.0/) answers it all the same: ported back, the mod's call puts and
 * ends. And 2.0's Hud.tick(Clock) is 1.0's Hud.tick(float), as 1.21's LayeredDraw.Layer took a DeltaTracker where
 * 1.20.6's takes the partial tick: the stand-in Clock (a class 1.0 lacks) says what a float makes of it (from(float)),
 * so the mod's override runs when 1.0's game calls tick(float), handed a Clock made from the float. And 2.0's
 * Panel.open(Ctx) is 1.0's Panel.open(Game), as NeoForge 21.0's config screen factory takes the mod's container where
 * 20.6's takes the game: Ctx is in both releases, so its stand-in is a holder whose from(Game) gives the Ctx itself.
 */
final class JarPortStandinFirstTest {

    static final String NEWER_SINK = """
        package net.minecraft.v7;
        public class Sink {
            public final StringBuilder log = new StringBuilder();
            public Sink add(float v) {
                log.append("add" + (int) v + ";");
                return this;
            }
        }
        """;

    static final String NEWER_CLOCK = """
        package net.minecraft.v7;
        public interface Clock {
            float partial();
        }
        """;

    static final String NEWER_HUD = """
        package net.minecraft.v7;
        public class Hud {
            public void tick(Clock clock) {
            }
        }
        """;

    static final String CTX = """
        package net.minecraft.v7;
        public class Ctx {
            public final String id;
            public Ctx(String id) {
                this.id = id;
            }
        }
        """;

    static final String GAME = """
        package net.minecraft.v7;
        public class Game {
        }
        """;

    static final String NEWER_PANEL = """
        package net.minecraft.v7;
        public class Panel {
            public void open(Ctx ctx) {
            }
        }
        """;

    static final String OLDER_PANEL = """
        package net.minecraft.v7;
        public class Panel {
            public void open(Game game) {
            }
        }
        """;

    static final String PANE = """
        package n7;
        import net.minecraft.v7.Ctx;
        import net.minecraft.v7.Panel;
        public class Pane extends Panel {
            public String seen = "none";
            @Override
            public void open(Ctx ctx) {
                seen = ctx.id;
            }
        }
        """;

    static final String STANDIN_CTX = """
        package mmt.standin.net.minecraft.v7;
        public final class Ctx {
            private Ctx() {}
            public static net.minecraft.v7.Ctx from(net.minecraft.v7.Game game) {
                return new net.minecraft.v7.Ctx("from the game");
            }
        }
        """;

    static final String OLDER_SINK = """
        package net.minecraft.v7;
        public class Sink {
            public final StringBuilder log = new StringBuilder();
            public Sink put(float v) {
                log.append("put" + (int) v + ";");
                return this;
            }
            public void end() {
                log.append("end;");
            }
        }
        """;

    static final String OLDER_HUD = """
        package net.minecraft.v7;
        public class Hud {
            public void tick(float partial) {
            }
        }
        """;

    static final String MOD = """
        package n7;
        import net.minecraft.v7.Clock;
        import net.minecraft.v7.Hud;
        import net.minecraft.v7.Sink;
        public class Mine extends Hud {
            public float seen = -1;
            @Override
            public void tick(Clock clock) {
                seen = clock.partial();
            }
            public static void draw(Sink sink) {
                sink.add(3);
            }
        }
        """;

    static final String STANDIN_SINK = """
        package mmt.standin.net.minecraft.v7;
        public final class Sink {
            private Sink() {}
            public static net.minecraft.v7.Sink add(net.minecraft.v7.Sink self, float v) {
                self.put(v);
                self.end();
                return self;
            }
        }
        """;

    static final String STANDIN_CLOCK = """
        package mmt.standin.net.minecraft.v7;
        public interface Clock {
            float partial();
            static Clock from(float partial) {
                return new Fixed(partial);
            }
            final class Fixed implements Clock {
                final float value;
                Fixed(float value) {
                    this.value = value;
                }
                public float partial() {
                    return value;
                }
            }
        }
        """;

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]).resolve("standin-first"));
        Map<String, byte[]> newer = JarPortBackTest.compile(scratch.resolve("newer"),
            Map.of("net/minecraft/v7/Sink.java", NEWER_SINK, "net/minecraft/v7/Clock.java", NEWER_CLOCK, "net/minecraft/v7/Hud.java", NEWER_HUD,
                "net/minecraft/v7/Ctx.java", CTX, "net/minecraft/v7/Panel.java", NEWER_PANEL), null);
        Map<String, byte[]> older = JarPortBackTest.compile(scratch.resolve("older"),
            Map.of("net/minecraft/v7/Sink.java", OLDER_SINK, "net/minecraft/v7/Hud.java", OLDER_HUD,
                "net/minecraft/v7/Ctx.java", CTX, "net/minecraft/v7/Game.java", GAME, "net/minecraft/v7/Panel.java", OLDER_PANEL), null);
        Map<String, byte[]> mod = JarPortBackTest.compile(scratch.resolve("mod"), Map.of("n7/Mine.java", MOD, "n7/Pane.java", PANE), scratch.resolve("newer/classes"));

        GameDiff.Snap a = GameDiff.read("2.0", new ArrayList<>(newer.values()), null, null);
        GameDiff.Snap b = GameDiff.read("1.0", new ArrayList<>(older.values()), null, null);
        b.loaderVersion = "1.0.1";
        Path map = scratch.resolve("route.json");
        Files.writeString(map, """
            {"signatures": [{"owner": "net.minecraft.v7.Sink", "from": "add", "desc": "(F)Lnet/minecraft/v7/Sink;", "to": "put", "newDesc": "(F)Lnet/minecraft/v7/Sink;"},
                            {"owner": "net.minecraft.v7.Hud", "from": "tick", "desc": "(Lnet/minecraft/v7/Clock;)V", "to": "tick", "newDesc": "(F)V"},
                            {"owner": "net.minecraft.v7.Panel", "from": "open", "desc": "(Lnet/minecraft/v7/Ctx;)V", "to": "open", "newDesc": "(Lnet/minecraft/v7/Game;)V"}]}
            """);
        Path standins = scratch.resolve("standins/2.0-1.0/net/minecraft/v7");
        Files.createDirectories(standins);
        Files.writeString(standins.resolve("Sink.java"), STANDIN_SINK);
        Files.writeString(standins.resolve("Clock.java"), STANDIN_CLOCK);
        Files.writeString(standins.resolve("Ctx.java"), STANDIN_CTX);

        Map<String, byte[]> files = new HashMap<>();
        mod.forEach((name, bytes) -> files.put(name + ".class", bytes));
        files.put("META-INF/neoforge.mods.toml", "modLoader=\"javafml\"\n[[mods]]\nmodId=\"n7\"\n".getBytes(StandardCharsets.UTF_8));

        JarPort port = new JarPort(a, b, map);
        port.oldCode = new GameCode(newer::get, null, newer);
        port.newCode = new GameCode(older::get, null, older);
        port.standIn.standins(List.of(scratch.resolve("standins/2.0-1.0")));
        List<Map<String, Object>> runs = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(files), "standin-first.jar", runs));
        List<String> unlinked = Json.array(runs.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(unlinked.isEmpty(), "the mod links against 1.0 with the stand-ins: " + unlinked);

        ClassLoader loader = JarPortBackStandinTest.load(older, ported);
        String log, opened;
        float seen;
        try {
            Class<?> mine = loader.loadClass("n7.Mine"), sink = loader.loadClass("net.minecraft.v7.Sink"), hud = loader.loadClass("net.minecraft.v7.Hud");
            Object s = sink.getConstructor().newInstance();
            mine.getMethod("draw", sink).invoke(null, s);
            log = String.valueOf(sink.getField("log").get(s));
            Object m = mine.getConstructor().newInstance();
            // As 1.0's game calls it: Hud.tick(float), on the mod's subclass.
            hud.getMethod("tick", float.class).invoke(m, 0.25F);
            seen = mine.getField("seen").getFloat(m);
            Class<?> pane = loader.loadClass("n7.Pane"), panel = loader.loadClass("net.minecraft.v7.Panel"), game = loader.loadClass("net.minecraft.v7.Game");
            Object p = pane.getConstructor().newInstance();
            // As 1.0's game calls it: Panel.open(Game).
            panel.getMethod("open", game).invoke(p, game.getConstructor().newInstance());
            opened = String.valueOf(pane.getField("seen").get(p));
        } catch (ReflectiveOperationException | LinkageError failed) {
            log = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
            seen = Float.NaN;
            opened = log;
        }
        JarPortTest.expect(log.equals("put3;end;"), "the mod's add runs the stand-in, which ends the entry, not the route's put alone: " + log);
        JarPortTest.expect(seen == 0.25F, "1.0's tick(float) runs the mod's override with a Clock made from the float: " + seen);
        JarPortTest.expect(opened.equals("from the game"), "1.0's open(Game) runs the mod's override with the Ctx the holder's from(Game) makes: " + opened);
        JarPortTest.finish("JarPortStandinFirstTest");
    }
}
