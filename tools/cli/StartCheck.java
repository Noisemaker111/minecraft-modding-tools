import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * start-check: whether mod jars start on a release, decided without a world or a window.
 *
 * Each side is the loader's own launch (the installed server's; the client's as a launcher writes it) in a new folder,
 * in one cold JVM under tools/nogame/MmtNoGame.java. That agent lets the loader resolve the mods, has Mixin apply every
 * mixin of every config as the game's main begins, and lets the game's own start-up code run (Bootstrap, the mods' start code, and on a server the
 * data packs of a new world), then ends the JVM where the game would open its world or its window. Nothing of an earlier
 * run is read: no kept process, no kept answer. What is kept is what the loader's installer and its first start write
 * for a release (PackLoaders, firstStart), as on any install of it.
 *
 * A run that fails names the jars at fault, read as pack-check reads a failed start (PackChecks.suspects). Those are
 * failed with the loader's, Mixin's or the game's own words and left out, and the rest run again, so every jar ends
 * failed, left out of the server as client-only, or passed, with the checks that passing covers named for each side.
 * A jar Mixin refused is started once more with only the jars it requires: when its mixins apply there, two jars here
 * change the same game code and it is a clash, the pairing's fault and not the jar's own. A jar that was only near a
 * failure nobody was named for is started once more with the jars that start, and passes when that start does.
 */
final class StartCheck {

    private StartCheck() {}

    static final String SERVER_STOP = "the game started its server thread";
    static final String CLIENT_STOP = "the game asked for its window";
    static final String MIXINS_STOP = "the mixins were read";
    /** The game's own line for the data packs it read (recipes since 1.14, advancements beside them). */
    static final Pattern DATA_READ = Pattern.compile("Loaded \\d+ (?:recipes|advancements)");

    /** What one side's runs decided. */
    static final class Side {

        final String name;
        final Map<String, String> failed = new TreeMap<>();
        final Map<String, String> leftOut = new TreeMap<>();
        /** Jars whose mixins apply with only the jars they require and not next to the others here: the pairing's fault, with what Mixin said. */
        final Map<String, String> clash = new TreeMap<>();
        /** The failed jars that Mixin refused (the others the loader or the game's start refused). */
        final Set<String> byMixin = new LinkedHashSet<>();
        /** The failed jars no one named: only near a failure (atFault). Each is started again with the jars that start. */
        final Set<String> unsure = new LinkedHashSet<>();
        final List<String> passed = new ArrayList<>();
        final List<Object> runs = new ArrayList<>();
        final Map<String, String> checks = new LinkedHashMap<>();
        /** Why the side has no answer for the jars still in it: a start that failed and named none of them, or a tool fault. */
        String undecided;
        boolean tool;

        Side(String name) {
            this.name = name;
        }
    }

    /** One launch under the agent: its log, the agent's events and how it ended. */
    record Launch(String log, List<Map<String, Object>> events, Path logFile, double seconds, long wall, boolean timedOut) {
        Map<String, Object> stop() {
            for (var event : events) if ("stop".equals(event.get("event"))) return event;
            return null;
        }
    }

    /**
     * --jars: the jars the answer is asked for, when not every jar (a ported mod, beside the libraries it needs). A side
     * ends once each of them is decided: the others are only there for them, and get a verdict only if a run gave one.
     */
    static Set<String> asked(PackCheck owner) {
        Set<String> asked = new LinkedHashSet<>();
        for (String jar : owner.options.get("jars", "").split(",")) if (!jar.isBlank()) asked.add(jar.strip());
        return asked;
    }

    static boolean decided(Side side, String jar) {
        return side.failed.containsKey(jar) || side.clash.containsKey(jar) || side.leftOut.containsKey(jar);
    }

    static boolean answered(PackCheck owner, Side side) {
        Set<String> asked = asked(owner);
        return !asked.isEmpty() && asked.stream().allMatch(jar -> side.failed.containsKey(jar) || side.leftOut.containsKey(jar));
    }

    static Map<String, Object> run(PackCheck owner, PackCheck.Pack pack, PackLoaders.Context context, Path install, Map<String, Object> result) throws Exception {
        String sides = owner.options.get("side", "both");
        if (!List.of("server", "client", "both").contains(sides)) throw new IllegalArgumentException("--side takes server, client or both; got " + sides);
        Path agent = agent(context);
        List<Side> done = new ArrayList<>();
        ExecutorService beside = Executors.newFixedThreadPool(2);
        try {
            // The two sides are two JVMs with nothing in common, so they run side by side.
            Future<Side> server = sides.equals("client") ? null : beside.submit(() -> server(owner, pack, context, install, agent));
            Future<Side> client = sides.equals("server") ? null : beside.submit(() -> client(owner, pack, context, agent));
            for (Future<Side> side : new ArrayList<>(java.util.Arrays.asList(server, client))) {
                if (side == null) continue;
                try {
                    done.add(side.get());
                } catch (ExecutionException failed) {
                    throw failed.getCause() instanceof Exception e ? e : failed;
                }
            }
        } finally {
            beside.shutdownNow();
        }
        Map<String, Object> mods = new TreeMap<>();
        Map<String, Object> bySide = new LinkedHashMap<>();
        boolean ok = true;
        for (Side side : done) {
            for (PackCheck.Mod mod : pack.mods()) {
                String verdict = verdict(side, mod.file());
                if (verdict != null) Json.object(mods.computeIfAbsent(mod.file(), k -> new LinkedHashMap<String, Object>())).put(side.name, verdict);
            }
            for (var entry : side.failed.entrySet()) owner.findings.add(Json.map("kind", "start-check", "severity", "problem", "summary",
                entry.getKey() + " does not start on the " + side.name + ": " + entry.getValue(),
                "suspects", List.of(Json.map("jar", entry.getKey(), "mod", PackCheck.mod(owner.scan, entry.getKey()), "why", List.of(entry.getValue())))));
            for (var entry : side.clash.entrySet()) owner.findings.add(Json.map("kind", "start-check", "severity", "problem", "summary",
                entry.getKey() + " does not start next to the other jars on the " + side.name + ", and its mixins apply without them: " + entry.getValue(),
                "suspects", List.of(Json.map("jar", entry.getKey(), "mod", PackCheck.mod(owner.scan, entry.getKey()), "why", List.of(entry.getValue())))));
            if (side.undecided != null) owner.findings.add(Json.map("kind", "start-check", "severity", "problem", "summary",
                "The " + side.name + " start gave no answer for " + (side.runs.isEmpty() ? "any jar" : "the jars still in it") + ": " + side.undecided));
            ok &= side.failed.isEmpty() && side.clash.isEmpty() && side.undecided == null;
            bySide.put(side.name, Json.map("failed", side.failed, "clash", side.clash, "leftOut", side.leftOut, "passed", side.passed, "undecided", side.undecided, "tool", side.tool,
                "checks", side.checks, "runs", side.runs));
        }
        result.put("sides", bySide);
        result.put("verdicts", mods);
        result.put("findings", owner.findings);
        result.put("ok", ok);
        result.put("details", text(done, pack));
        return result;
    }

    /** A jar's verdict on a side, in words that say what was and was not run; null for a jar the side never held. */
    static String verdict(Side side, String jar) {
        if (side.failed.containsKey(jar)) return "failed: " + side.failed.get(jar);
        if (side.clash.containsKey(jar)) return "clash: its mixins apply with only the jars it requires, and not next to the other jars here: " + side.clash.get(jar);
        if (side.leftOut.containsKey(jar)) return "left out: " + side.leftOut.get(jar);
        if (side.passed.contains(jar)) return "passed: " + String.join("; ", ran(side)) + " (no world or window was opened)";
        if (side.undecided != null) return "undecided: " + side.undecided;
        return side.runs.isEmpty() ? null : "undecided: the side ended once every jar asked for (--jars) was decided";
    }

    static List<String> ran(Side side) {
        List<String> ran = new ArrayList<>();
        for (var check : side.checks.entrySet()) if (!check.getValue().startsWith("not checked")) ran.add(check.getKey());
        return ran;
    }

    static String text(List<Side> sides, PackCheck.Pack pack) {
        StringBuilder out = new StringBuilder();
        for (Side side : sides) {
            out.append(side.name).append(": ").append(side.passed.size()).append(" passed, ").append(side.failed.size()).append(" failed");
            if (!side.clash.isEmpty()) out.append(", ").append(side.clash.size()).append(" clash with another jar");
            if (!side.leftOut.isEmpty()) out.append(", ").append(side.leftOut.size()).append(" left out");
            out.append(" of ").append(side.passed.size() + side.failed.size() + side.clash.size() + side.leftOut.size()).append(" jar(s), in ").append(side.runs.size()).append(" run(s)\n");
            for (var entry : side.failed.entrySet()) out.append("  failed  ").append(entry.getKey()).append(": ").append(entry.getValue().lines().findFirst().orElse("")).append('\n');
            for (var entry : side.clash.entrySet()) {
                out.append("  clash  ").append(entry.getKey()).append(": its mixins apply with only the jars it requires; next to the other jars: ");
                out.append(entry.getValue().lines().findFirst().orElse("")).append('\n');
            }
            for (var entry : side.leftOut.entrySet()) out.append("  left out  ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
            if (side.undecided != null) out.append("  no answer: ").append(side.undecided).append('\n');
            for (var check : side.checks.entrySet()) out.append("  ").append(check.getKey()).append(": ").append(check.getValue()).append('\n');
        }
        out.append("Passing means the checks named above found no fault. No world or window was opened, so nothing in a world is covered.");
        return out.toString();
    }

    // ------------------------------------------------------------------ the server
    static Side server(PackCheck owner, PackCheck.Pack pack, PackLoaders.Context context, Path install, Path agent) throws Exception {
        Side side = new Side("server");
        Path first = firstStart(owner, context, pack, install, agent, "server");
        Set<String> clientOnly = new LinkedHashSet<>();
        Holding holding = () -> {
            PackCheck.ServerMods chosen = PackCheck.serverMods(pack.mods(), owner.scan, clientOnly, side.failed.keySet());
            side.leftOut.clear();
            side.leftOut.putAll(chosen.leftOut());
            return pack.mods().stream().filter(m -> chosen.kept().contains(m.file())).toList();
        };
        // A start that failed and named no jar: the loader's own client-only words name a mod a server cannot run, left out as pack-check leaves it.
        Unnamed unnamed = (run, mods) -> {
            Set<String> named = new LinkedHashSet<>();
            for (String id : PackChecks.clientOnly(run.log(), owner.loader, owner.scan, owner.game::clientOnly)) {
                for (PackMods.Provider p : owner.scan.providers.getOrDefault(id, List.of())) if (!p.bundled() && mods.stream().anyMatch(m -> m.file().equals(p.jar()))) named.add(p.jar());
            }
            if (clientOnly.addAll(named)) return true;
            side.undecided = "the start failed on client-only code and named none of the jars; its log: " + run.logFile();
            return false;
        };
        Launch last = rounds(owner, pack, side, first, install, (instance, asked) -> serverCommand(owner, context, pack, install, agent, instance, asked), holding, unnamed);
        boolean reached = started(owner, side, last);
        if (reached) for (PackCheck.Mod mod : pack.mods()) if (!decided(side, mod.file())) side.passed.add(mod.file());
        boolean data = reached && DATA_READ.matcher(last.log()).find();
        side.checks.put("dependencies", dependencies(owner, last));
        side.checks.put("mixins", mixins(last));
        side.checks.put("start code", reached ? "the game's Bootstrap and the mods' own start code ran, as the dedicated server's main runs them"
            : "not checked: no run reached the game's server thread");
        side.checks.put("data", data ? "the game read the data packs of a new world (recipes, tags, registries) before it would open one"
            : reached ? "not checked: this release reads its data packs only once a world is open (its log names none before the server thread)"
            : "not checked: no run reached the game's server thread");
        return side;
    }

    /**
     * What a run says: null when it ends the side (it reached the stop with nothing refused, or failed naming no jar),
     * else the jars it fails with why; empty when it failed and names none directly (the caller asks for client-only code).
     */
    static Map<String, String> judge(PackCheck owner, Side side, Launch run, Path instance, List<PackCheck.Mod> mods, int round) {
        Set<String> present = new LinkedHashSet<>();
        for (PackCheck.Mod mod : mods) present.add(mod.file());
        Map<String, Object> stop = run.stop();
        Map<String, String> refused = refusedByMixin(owner, run, present);
        boolean reached = stop != null && reached(side, stop);
        side.runs.add(Json.map("round", round, "jars", mods.size(), "seconds", run.seconds(), "log", run.logFile().toString(),
            "ended", stop == null ? "by itself" : stop.get("reason"), "at", stop == null ? null : stop.get("seconds"), "failed", new ArrayList<>(refused.keySet())));
        if (reached && refusedInLog(owner, run)) Json.object(side.runs.get(side.runs.size() - 1)).put("loaderRefused", true);
        side.byMixin.addAll(refused.keySet());
        if (!refused.isEmpty()) return refused;
        if (run.timedOut()) {
            side.undecided = "the start said nothing more for " + owner.options.get("timeout", "300") + " s and was stopped; its log: " + run.logFile();
            side.tool = true;
            return null;
        }
        if (started(owner, side, run)) return null;
        if (stop != null && !reached) {
            side.undecided = "the check ended early (" + stop.get("reason") + ") and named none of the jars; its log: " + run.logFile();
            side.tool = true;
            return null;
        }
        Map<String, String> blamed = new LinkedHashMap<>();
        // A mod that reaches client-only code stops a server and is left out of it, not failed: asked first, as pack-check's own start asks.
        if (side.name.equals("server") && !PackChecks.clientOnly(run.log(), owner.loader, owner.scan, owner.game::clientOnly).isEmpty()) return blamed;
        List<String> reports = PackChecks.crashReports(instance, run.wall());
        String failure = PackChecks.failure(run.log() + "\n" + String.join("\n", reports), owner.loader.failed());
        blamed.putAll(atFault(PackChecks.suspects(run.log(), reports, owner.scan, owner.loader, owner.game::clientOnly), present, side.unsure));
        // A jar the loader refuses for a mod an earlier run failed says why that one failed too: its own port may be sound.
        for (var entry : blamed.entrySet()) {
            for (var d : owner.scan.dependencies) {
                if (!d.kind().equals("required") || !PackCheck.top(d.owner()).equals(entry.getKey())) continue;
                List<String> gives = owner.scan.providers.getOrDefault(d.id(), List.of()).stream().map(p -> PackCheck.top(p.jar())).toList();
                String gone = gives.stream().filter(side.failed::containsKey).findFirst().orElse(null);
                if (gone == null || gives.stream().anyMatch(present::contains)) continue;
                entry.setValue(entry.getValue() + "; it needs " + gone + ", which failed: " + side.failed.get(gone));
                break;
            }
        }
        if (!blamed.isEmpty()) return blamed;
        side.undecided = "the start failed and named none of the jars: " + failure;
        return null;
    }

    /** The evidence that puts a jar only near a failure: its code, a mixin handler or a mixin config of it in a stack that may only pass through it. */
    static final List<String> NEAR = List.of("its code (", "a mixin handler of this mod", "its mixin config ", "the crash report suspects it");

    /**
     * The jars a failed start's suspects put at fault, with why. A jar the loader or the game names in its own words is
     * at fault, and with one named, a jar only near the failure is a bystander: a data pack the release refuses failed
     * with another mod's mixin handler in its stack, and that mod loads. With nobody named, the jars near the failure are
     * failed. Jars failed only for being near, and jars one start names together, are noted in unsure: each is started
     * again once the rest starts. Several named at once may be one fault and the mods it surfaced in: the loader names the
     * mod whose class was the first to load when another mod's mixin could not be prepared.
     */
    static Map<String, String> atFault(List<Map<String, Object>> suspects, Set<String> present, Set<String> unsure) {
        Map<String, String> named = new LinkedHashMap<>(), near = new LinkedHashMap<>();
        for (var suspect : suspects) {
            String jar = PackCheck.top(Json.text(suspect, "jar", ""));
            if (!present.contains(jar)) continue;
            List<String> why = Json.array(suspect.get("why")).stream().map(String::valueOf).toList();
            boolean onlyNear = why.stream().allMatch(w -> NEAR.stream().anyMatch(w::startsWith));
            (onlyNear ? near : named).putIfAbsent(jar, String.join("; ", why));
        }
        Map<String, String> fault = named.isEmpty() ? near : named;
        if (named.isEmpty() || named.size() > 1) unsure.addAll(fault.keySet());
        return fault;
    }

    /** What a side does with a start that failed and named no jar (run, the jars it held): true to go on to another round. */
    interface Unnamed {
        boolean goOn(Launch run, List<PackCheck.Mod> mods) throws Exception;
    }

    /**
     * A side's rounds: the jars it holds are started, the ones a failed start puts at fault are taken out, and the rest
     * start again, until a start reaches the side's end or names nobody. Then the jars that were only near a failure
     * are started again (again) and the ones Mixin refused are started alone (alone). Returns the last start.
     */
    static Launch rounds(PackCheck owner, PackCheck.Pack pack, Side side, Path first, Path install, Starter starter, Holding holding, Unnamed unnamed) throws Exception {
        Launch last = null;
        // Every round that does not end the side takes at least one jar out, so the rounds end.
        for (int round = 1;; round++) {
            List<PackCheck.Mod> mods = holding.mods();
            if (mods.isEmpty() && round > 1 || answered(owner, side)) break;
            String name = side.name + "-" + round;
            Path instance = instance(owner, pack, mods, name, first, install);
            last = launch(owner, starter.command(instance, side.name), instance, name);
            Map<String, String> blamed = judge(owner, side, last, instance, mods, round);
            tidy(instance);
            if (blamed == null) break;
            Json.object(side.runs.get(side.runs.size() - 1)).put("failed", new ArrayList<>(blamed.keySet()));
            if (blamed.isEmpty() && !unnamed.goOn(last, mods)) break;
            side.failed.putAll(blamed);
        }
        last = again(owner, pack, side, first, install, starter, holding, last);
        alone(owner, pack, side, first, install, starter);
        return last;
    }

    /** The jars a side holds now: every jar of the pack it runs, less the ones failed so far. */
    interface Holding {
        List<PackCheck.Mod> mods() throws Exception;
    }

    /**
     * Each unsure jar, started again with the jars that start: when that start reaches the side's end, the jar was a
     * bystander of a failure another jar caused, and it passes. Returns the last start that reached the end (else last).
     */
    static Launch again(PackCheck owner, PackCheck.Pack pack, Side side, Path first, Path install, Starter starter, Holding holding, Launch last) throws Exception {
        if (!started(owner, side, last)) return last;
        int run = 0;
        for (String jar : new ArrayList<>(side.unsure)) {
            String why = side.failed.remove(jar);
            if (why == null) continue;
            List<PackCheck.Mod> mods = holding.mods();
            String name = side.name + "-again-" + ++run;
            Path instance = instance(owner, pack, mods, name, first, install);
            Launch launch = launch(owner, starter.command(instance, side.name), instance, name);
            tidy(instance);
            Set<String> present = new LinkedHashSet<>();
            for (PackCheck.Mod mod : mods) present.add(mod.file());
            boolean starts = started(owner, side, launch) && refusedByMixin(owner, launch, present).isEmpty();
            side.runs.add(Json.map("again", jar, "jars", mods.size(), "seconds", launch.seconds(), "log", launch.logFile().toString(),
                "ended", launch.stop() == null ? "by itself" : launch.stop().get("reason"), "starts", starts));
            if (starts) last = launch;
            else side.failed.put(jar, why);
        }
        return last;
    }

    /** Whether a run ended where its side's check ends: the server thread, or the window (the window library, where its one call could not be replaced). */
    static boolean reached(Side side, Map<String, Object> stop) {
        String reason = String.valueOf(stop.get("reason"));
        return side.name.equals("server") ? reason.equals(SERVER_STOP) : reason.equals(CLIENT_STOP) || reason.equals("the game loaded its window library");
    }

    /**
     * Whether the loader's resolver refused jars of a run in its log (pack-loaders.json, refusedInLog). NeoForge's client
     * logs what its resolver refuses and goes on to its window, where it shows that on an error screen and loads no mod:
     * a run that got to the window with such a line is a failed start.
     */
    static boolean refusedInLog(PackCheck owner, Launch run) {
        List<Object> said = Json.array(((PackLoaders.Style) owner.loader).spec.getOrDefault("refusedInLog", List.of()));
        return said.stream().anyMatch(sentence -> run.log().contains(String.valueOf(sentence)));
    }

    /** Whether a run started: it ended where its side's check ends, and the loader's resolver refused none of its jars on the way. */
    static boolean started(PackCheck owner, Side side, Launch run) {
        return run != null && run.stop() != null && reached(side, run.stop()) && !refusedInLog(owner, run);
    }

    static final Pattern CONFIG_NAMED = Pattern.compile("([\\w.\\-]+\\.json)");
    static final Pattern MOD_NAMED = Pattern.compile("from mod ([a-z0-9_.\\-]+)");

    /**
     * The jars Mixin refused in a run. An error Mixin reports names the config of the mixin at fault. A game class that
     * does not load with its mixins is the fault of the mixin its error names (its config, else "from mod ID"): several
     * mods mix into one class, so the class's own configs say nothing. One whose error names none is left to the caller.
     * A class of a jar that is the other side's code (the agent's otherSide: a client screen, on a server) is loaded by
     * no start of this side: what Mixin says of it refuses nobody here.
     */
    static Map<String, String> refusedByMixin(PackCheck owner, Launch run, Set<String> present) {
        Map<String, String> refused = new LinkedHashMap<>();
        Set<String> otherSide = new LinkedHashSet<>();
        for (var event : run.events()) if ("target".equals(event.get("event")) && event.get("otherSide") != null) otherSide.add(Json.text(event, "target", ""));
        for (var event : run.events()) {
            boolean error = "error".equals(event.get("event")) && "ERROR".equals(event.get("action"));
            boolean unloadable = "target".equals(event.get("event")) && Boolean.FALSE.equals(event.get("loaded")) && !"java.lang.ClassNotFoundException".equals(event.get("exception"));
            if (!error && !unloadable || otherSide.contains(Json.text(event, "target", "").replace('/', '.'))) continue;
            String message = Json.text(event, "message", "");
            Set<String> jars = new LinkedHashSet<>();
            if (error) jars.add(PackCheck.top(owner.scan.mixins.getOrDefault(Json.text(event, "config", ""), "")));
            else {
                Matcher config = CONFIG_NAMED.matcher(message);
                while (config.find()) jars.add(PackCheck.top(owner.scan.mixins.getOrDefault(config.group(1), "")));
                Matcher mod = MOD_NAMED.matcher(message);
                while (jars.stream().noneMatch(present::contains) && mod.find()) {
                    for (PackMods.Provider p : owner.scan.providers.getOrDefault(mod.group(1), List.of())) jars.add(PackCheck.top(p.jar()));
                }
            }
            String said = message.replace("\n  caused by ", "; caused by ");
            String why = error
                ? "Mixin refuses its mixin " + event.get("config") + ":" + Json.text(event, "mixin", "?") + (event.get("target") == null ? "" : " on " + event.get("target")) + ": " + said
                : "the game's class " + event.get("target") + " does not load with its mixin: " + said;
            for (String jar : jars) if (present.contains(jar)) refused.putIfAbsent(jar, why.length() > 900 ? why.substring(0, 900) : why);
        }
        return refused;
    }

    /** The loader has resolved the jars once Mixin reads its configs (the agent's first event): it starts nothing it refuses. */
    static String dependencies(PackCheck owner, Launch last) {
        boolean resolved = last != null && !last.events().isEmpty() && !refusedInLog(owner, last);
        return resolved ? "the loader's own resolver accepted the jars of the last run: their dependencies, game version and Java"
            : "not checked: no run got past the loader";
    }

    static String mixins(Launch last) {
        if (last == null) return "not checked: nothing was started";
        int targets = 0, applied = 0, mixins = 0;
        boolean read = false;
        for (var event : last.events()) {
            if ("done".equals(event.get("event"))) read = true;
            if ("target".equals(event.get("event"))) targets++;
            if ("mixin".equals(event.get("event"))) {
                mixins++;
                if (Boolean.TRUE.equals(event.get("applied"))) applied++;
            }
        }
        if (!read) return "not checked: the start ended before the game's entry class loaded, where Mixin has read its configs";
        if (last.stop() != null && String.valueOf(last.stop().get("reason")).startsWith("Mixin refused")) return "not checked for the jars left: the last run ended on a mixin Mixin refused";
        return "Mixin applied the mixins of every config to all " + targets + " game classes they target on this side (" + applied + " of " + mixins + " mixins took part)";
    }

    // ------------------------------------------------------------------ the client
    static Side client(PackCheck owner, PackCheck.Pack pack, PackLoaders.Context context, Path agent) throws Exception {
        Side side = new Side("client");
        Path install = PackClient.install(context, owner.loader, pack.minecraft(), pack.loaderVersion());
        if (install == null) {
            side.undecided = "start-check cannot start " + owner.loader.id() + "'s client: knowledge/pack-loaders.json gives it no client installer";
            side.tool = true;
            return side;
        }
        PackClient.libraries(install, pack.minecraft());
        Path first = firstStart(owner, context, pack, install, agent, "client");
        for (PackCheck.Mod mod : pack.mods()) if ("server".equals(mod.side())) side.leftOut.put(mod.file(), "server-only (" + mod.source() + ")");
        Holding holding = () -> pack.mods().stream().filter(m -> !"server".equals(m.side()) && !side.failed.containsKey(m.file())).toList();
        Launch last = rounds(owner, pack, side, first, install, (instance, asked) -> clientCommand(owner, context, pack, install, agent, instance, asked), holding, (run, mods) -> false);
        boolean reached = started(owner, side, last);
        if (reached) for (PackCheck.Mod mod : pack.mods()) if (!decided(side, mod.file())) side.passed.add(mod.file());
        side.checks.put("dependencies", dependencies(owner, last));
        side.checks.put("mixins", mixins(last));
        boolean window = reached && String.valueOf(last.stop().get("reason")).equals(CLIENT_STOP);
        side.checks.put("start code", !reached ? "not checked: no run reached the point where the game asks for its window"
            : !window ? "not checked: the check ended as the game loaded its window library, before its start code"
            : startsModsBeforeWindow(owner) ? "the game's client Bootstrap and the mods' own client start code ran, up to where the game asks for its window"
            : "the game's client Bootstrap ran, up to where the game asks for its window; not checked: the mods' own start code, which this loader runs once the window is there");
        side.checks.put("resources", "not checked: the game reads models, textures and language files once it has a window");
        return side;
    }

    /** Whether the loader runs the mods' client start code before the game makes its window (pack-loaders.json, startsModsBeforeWindow). */
    static boolean startsModsBeforeWindow(PackCheck owner) {
        return Boolean.TRUE.equals(((PackLoaders.Style) owner.loader).spec.get("startsModsBeforeWindow"));
    }

    /**
     * A loader that opens a window of its own before the game's (NeoForge's loading window, made before it has read a
     * mod) is told not to, in its own settings file in the run's folder (pack-loaders.json, ownWindow): the check then
     * ends where the game asks for its window, with the mods read and their mixins applied.
     */
    static void noOwnWindow(PackCheck owner, Path instance) throws IOException {
        var setting = Json.object(((PackLoaders.Style) owner.loader).spec.getOrDefault("ownWindow", Map.of()));
        if (setting.isEmpty()) return;
        Path file = instance.resolve(Json.text(setting, "file", ""));
        List<String> lines = new ArrayList<>();
        // The pack's own copy of the file keeps its other settings.
        if (Files.isRegularFile(file)) for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) if (!line.strip().startsWith(Json.text(setting, "key", ""))) lines.add(line);
        lines.add(Json.text(setting, "off", ""));
        Files.createDirectories(file.getParent());
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    /** One start of the loader's server, in a folder made for it. asked: what the agent is asked for ("server", or "server,mixins"). */
    static List<String> serverCommand(PackCheck owner, PackLoaders.Context context, PackCheck.Pack pack, Path install, Path agent, Path instance, String asked) throws Exception {
        writeServerFiles(instance);
        List<String> command = new ArrayList<>(jvm(owner, context, agent, asked, instance));
        command.addAll(owner.loader.launch(install, instance, pack.minecraft(), pack.loaderVersion()));
        command.add("nogui");
        return command;
    }

    /** How a side starts a folder: (the folder, what the agent is asked for) -> the command. */
    interface Starter {
        List<String> command(Path instance, String asked) throws Exception;
    }

    /** The jar and every jar it requires, and those in turn, of the pack's own files; null when one of them failed (its start alone would fail for that). */
    static List<PackCheck.Mod> withRequired(PackCheck owner, PackCheck.Pack pack, Side side, String jar) {
        Set<String> wanted = new LinkedHashSet<>(List.of(jar));
        List<String> todo = new ArrayList<>(List.of(jar));
        while (!todo.isEmpty()) {
            String next = todo.remove(todo.size() - 1);
            for (var d : owner.scan.dependencies) {
                if (!d.kind().equals("required") || !PackCheck.top(d.owner()).equals(next)) continue;
                for (PackMods.Provider p : owner.scan.providers.getOrDefault(d.id(), List.of())) if (wanted.add(PackCheck.top(p.jar()))) todo.add(PackCheck.top(p.jar()));
            }
        }
        wanted.remove(jar);
        if (wanted.stream().anyMatch(side.failed::containsKey)) return null;
        wanted.add(jar);
        return pack.mods().stream().filter(m -> wanted.contains(m.file())).toList();
    }

    /**
     * Each jar Mixin refused, started once more with only the jars it requires and asked only whether its mixins apply.
     * When they do, the jar is not at fault alone: another jar here changes the same game code (two mods redirect one
     * call), which fails on any release, ported or not. It moves from failed to clash, with what Mixin said.
     */
    static void alone(PackCheck owner, PackCheck.Pack pack, Side side, Path first, Path install, Starter starter) throws Exception {
        int run = 0;
        for (String jar : new ArrayList<>(side.byMixin)) {
            List<PackCheck.Mod> mods = side.failed.containsKey(jar) ? withRequired(owner, pack, side, jar) : null;
            if (mods == null || mods.size() == pack.mods().size()) continue;
            String name = side.name + "-alone-" + ++run;
            Path instance = instance(owner, pack, mods, name, first, install);
            Launch launch = launch(owner, starter.command(instance, side.name + ",mixins"), instance, name);
            tidy(instance);
            Set<String> present = new LinkedHashSet<>();
            for (PackCheck.Mod mod : mods) present.add(mod.file());
            boolean applied = launch.stop() != null && MIXINS_STOP.equals(launch.stop().get("reason")) && refusedByMixin(owner, launch, present).isEmpty();
            side.runs.add(Json.map("alone", jar, "jars", mods.size(), "seconds", launch.seconds(), "log", launch.logFile().toString(),
                "ended", launch.stop() == null ? "by itself" : launch.stop().get("reason"), "mixinsApply", applied));
            if (applied) side.clash.put(jar, side.failed.remove(jar));
        }
    }

    static List<String> clientCommand(PackCheck owner, PackLoaders.Context context, PackCheck.Pack pack, Path install, Path agent, Path instance, String asked) throws Exception {
        // The native code old releases ask for by folder; nothing draws, but a mod's start code may load LWJGL's classes.
        PackClient.natives(install, pack.minecraft(), Files.createDirectories(instance.resolve("natives")));
        noOwnWindow(owner, instance);
        List<String> options = new ArrayList<>(jvm(owner, context, agent, asked, instance));
        options.remove(0);
        // The assets folder is only named: the game reads it once it has a window.
        return PackClient.command(install, context.cache().resolve("assets"), pack.minecraft(), context.java().java(), instance, options);
    }

    // ------------------------------------------------------------------ one launch
    /**
     * java and the options every start-check launch has: little memory, the quick compiler only (a start is all it runs),
     * the agent (asked for a side) and its file, and no network. The game asks Mojang's services who the player is as it
     * starts (by each service's own address before 26.1, through one discovery address since), and a mod's start code may
     * look for an update: a check has no player and asks nothing outside this machine, so those addresses and every
     * proxy Java connects through point at a port of this machine nothing listens on, which refuses at once.
     */
    static List<String> jvm(PackCheck owner, PackLoaders.Context context, Path agent, String asked, Path instance) {
        List<String> command = new ArrayList<>(List.of(context.java().java().toString(), "-Xmx" + owner.options.get("memory", "2G"), "-XX:TieredStopAtLevel=1",
            "-javaagent:" + agent + "=" + asked, "-Dmmt.mixincheck.out=" + instance.resolve("mmt-start-check.jsonl"), "-Djava.awt.headless=true"));
        for (String host : List.of("auth", "account", "session", "services", "profiles", "discovery")) command.add("-Dminecraft.api." + host + ".host=http://127.0.0.1:9");
        for (String proxy : List.of("http.proxy", "https.proxy", "socksProxy")) {
            command.add("-D" + proxy + "Host=127.0.0.1");
            command.add("-D" + proxy + "Port=9");
        }
        return command;
    }

    static void writeServerFiles(Path instance) throws IOException {
        // Accepted by mmt only so the game's main goes on to read its data: the server it would then start is never run.
        Files.writeString(instance.resolve("eula.txt"), "# Accepted by mmt start-check: the game's main runs up to where it would start its server thread, and no further.\neula=true\n");
        Files.writeString(instance.resolve("server.properties"), "server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nlevel-name=world\nmotd=mmt start-check\n");
    }

    /** A new folder for one launch, in the run's receipt: the jars linked into mods/, the pack's own settings, and the loader's first-start files. */
    static Path instance(PackCheck owner, PackCheck.Pack pack, List<PackCheck.Mod> mods, String name, Path first, Path install) throws IOException {
        Path instance = owner.report.directory.resolve(name);
        PackCheck.deleteTree(instance);
        Files.createDirectories(instance.resolve("mods"));
        for (String folder : List.of("config", "defaultconfigs", "kubejs", "scripts")) {
            for (Path layer : pack.layers()) if (Files.isDirectory(layer.resolve(folder))) PackCheck.copyTree(layer.resolve(folder), instance.resolve(folder));
        }
        for (PackCheck.Mod mod : mods) PackCheck.link(mod.jar(), instance.resolve("mods").resolve(mod.file()));
        if (first != null) linkTree(first, instance);
        return instance;
    }

    /**
     * What a run's folder keeps for its receipt is what the start itself wrote (its logs, crash reports and the agent's
     * events): the jars, the loader's files and the native code a client unpacks are links and copies of what is elsewhere.
     */
    static void tidy(Path instance) {
        for (String folder : List.of("mods", "libraries", "versions", ".fabric", "natives", "world")) {
            try {
                PackCheck.deleteTree(instance.resolve(folder));
            } catch (IOException held) {
                // Windows can hold a library a moment after its JVM ended; a leftover copy harms nothing.
            }
        }
    }

    static void linkTree(Path from, Path to) throws IOException {
        try (var walk = Files.walk(from)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                Path target = to.resolve(from.relativize(file).toString());
                if (file.getFileName().toString().equals("mmt-installed")) continue;
                Files.createDirectories(target.getParent());
                PackCheck.link(file, target);
            }
        }
    }

    static Launch launch(PackCheck owner, List<String> command, Path instance, String name) throws Exception {
        Path log = owner.report.log(name);
        Path events = instance.resolve("mmt-start-check.jsonl");
        long wall = System.currentTimeMillis(), began = System.nanoTime();
        Process process = PackChecks.windowless(new ProcessBuilder(command)).directory(instance.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        process.getOutputStream().close();
        long quiet = TimeUnit.SECONDS.toNanos(Integer.parseInt(owner.options.get("timeout", "300")));
        long size = 0, grew = System.nanoTime(), failedAt = 0;
        boolean timedOut = false;
        List<String> failed = owner.loader.failed();
        while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
            long now = Files.size(log);
            if (now > size) {
                size = now;
                grew = System.nanoTime();
                // A start that failed can leave threads that keep its JVM alive; once it has said so it gets a moment to end.
                String text = Files.readString(log, StandardCharsets.ISO_8859_1);
                if (failedAt == 0 && failed.stream().anyMatch(text::contains)) failedAt = System.nanoTime();
            }
            boolean over = failedAt != 0 && System.nanoTime() - failedAt > TimeUnit.SECONDS.toNanos(5);
            timedOut = !over && System.nanoTime() - grew > quiet;
            if (over || timedOut) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                break;
            }
        }
        double seconds = (System.nanoTime() - began) / 1e9;
        owner.report.stages.add(Json.map("name", name, "seconds", seconds, "log", log.toString()));
        List<Map<String, Object>> read = new ArrayList<>();
        if (Files.isRegularFile(events)) for (String line : Files.readAllLines(events, StandardCharsets.UTF_8)) if (line.startsWith("{")) read.add(Json.object(Json.parse(line)));
        // Mods log in the system code page as well as UTF-8; a strict read would fail on the first such byte.
        String text = Files.isRegularFile(log) ? new String(Files.readAllBytes(log), StandardCharsets.UTF_8) : "";
        return new Launch(text, read, log, seconds, wall, timedOut);
    }

    // ------------------------------------------------------------------ what is made once
    /**
     * The files a loader writes into a new folder the first time it starts there, the same for every pack (Fabric's
     * game jar in the names it runs, the server's own libraries unpacked): made once per installed loader and side by one
     * start with no mods, as installOnce makes the install, and linked into each run's folder. null for a loader whose
     * installer leaves nothing for its first start to do (pack-loaders.json names the files, firstStart).
     */
    static Path firstStart(PackCheck owner, PackLoaders.Context context, PackCheck.Pack pack, Path install, Path agent, String side) throws Exception {
        List<Object> files = Json.array(Json.object(((PackLoaders.Style) owner.loader).spec.getOrDefault("firstStart", Map.of())).getOrDefault(side, List.of()));
        if (files.isEmpty()) return null;
        Path dir = context.cache().resolve("loaders").resolve(owner.loader.id() + "-first-" + side + "-" + pack.minecraft() + "-" + pack.loaderVersion());
        return PackLoaders.Style.installOnce(dir, folder -> {
            // Started in the run's own receipt folder: what it leaves beside the files asked for (logs, native code Windows
            // still holds for a moment) is no part of an install.
            Path instance = owner.report.directory.resolve("first-" + side);
            PackCheck.deleteTree(instance);
            Files.createDirectories(instance.resolve("mods"));
            List<String> command;
            if (side.equals("server")) command = serverCommand(owner, context, pack, install, agent, instance, side);
            else command = clientCommand(owner, context, pack, install, agent, instance, side);
            Launch made = launch(owner, command, instance, owner.loader.id() + "-first-" + side);
            if (made.stop() == null) {
                throw new IOException("The " + owner.loader.id() + " " + side + " did not start with no mods (see " + made.logFile() + "), so its first-start files were not made.");
            }
            for (Object file : files) {
                Path source = instance.resolve(String.valueOf(file));
                // A release writes the ones it has: a server from before 1.18 is one jar with no libraries of its own to
                // unpack, and a game that ships in its own names (26.1 on) is not rewritten into others.
                if (!Files.exists(source)) continue;
                // Copied, not moved: Windows refuses to move a folder while anything (a virus scanner reading the new jar) has a file of it open.
                PackCheck.copyTree(source, folder.resolve(String.valueOf(file)));
            }
            tidy(instance);
        });
    }

    /** The agent's sources: its own class, the mixin check it runs as the game's main begins, and the class-file reading it shares with MmtNeoForgeTarget. */
    static final List<String> AGENT_SOURCES = List.of("tools/nogame/MmtNoGame.java", "tools/mixincheck/MmtMixinCheck.java", "tools/mixincheck/MmtMixinErrors.java",
        "tools/mixincheck/MmtNeoForgeTarget.java");

    /** The agent's jar from its compiled classes. */
    static void agentJar(Path classes, Path jar) throws IOException {
        java.util.jar.Manifest manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", "MmtNoGame");
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar), manifest); var walk = Files.walk(classes)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                out.putNextEntry(new java.util.zip.ZipEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
    }

    /** The agent's jar, built once per content of its sources (as PackLoaders.spawnAgent builds its own). */
    static Path agent(PackLoaders.Context context) throws Exception {
        Path harness = context.harness();
        List<Path> sources = AGENT_SOURCES.stream().map(harness::resolve).toList();
        List<Path> stubs = new ArrayList<>();
        for (String stub : MixinCheck.STUBS) stubs.add(harness.resolve(stub));
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        for (Path p : sources) hash.update(Files.readAllBytes(p));
        for (Path p : stubs) hash.update(Files.readAllBytes(p));
        Path dir = context.cache().resolve("agents").resolve("no-game-" + HexFormat.of().formatHex(hash.digest()).substring(0, 16));
        Path jar = dir.resolve("mmt-nogame.jar");
        if (Files.isRegularFile(jar)) return jar;
        // Built in a folder of this run's own: start-checks run side by side on one machine.
        Files.createDirectories(dir);
        Path work = Files.createTempDirectory(dir, "classes-");
        try {
            String javac = Runtimes.ensureAtLeast(harness, 17).home().resolve("bin/" + (Runtimes.WINDOWS ? "javac.exe" : "javac")).toString();
            Path stubClasses = Files.createDirectories(work.resolve("stubs")), classes = Files.createDirectories(work.resolve("classes"));
            List<String> first = new ArrayList<>(List.of(javac, "--release", "8", "-Xlint:-options", "-nowarn", "-encoding", "UTF-8", "-d", stubClasses.toString()));
            stubs.forEach(p -> first.add(p.toString()));
            List<String> second = new ArrayList<>(List.of(javac, "--release", "8", "-Xlint:-options", "-nowarn", "-encoding", "UTF-8", "-cp", stubClasses.toString(), "-d", classes.toString()));
            sources.forEach(p -> second.add(p.toString()));
            for (List<String> command : List.of(first, second)) {
                var result = Run.command(command, dir, Map.of(), context.report().log("no-game-agent-compile"), 120);
                context.report().stage("no-game-agent-compile", result, Map.of());
                if (result.exit() != 0) throw new IOException("Could not build the start-check agent: " + Run.tail(result.log()));
            }
            Path temporary = work.resolve("mmt-nogame.jar");
            agentJar(classes, temporary);
            try {
                Files.move(temporary, jar, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException taken) {
                if (!Files.isRegularFile(jar)) throw taken;
            }
        } finally {
            PackCheck.deleteTree(work);
        }
        return jar;
    }
}
