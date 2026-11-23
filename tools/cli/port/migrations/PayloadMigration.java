import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Network packets the loader moved to custom payloads. */
final class PayloadMigration {

    final Port port;

    PayloadMigration(Port port) {
        this.port = port;
    }

    static String payloadCalls(String text, String name, Set<Integer> lines) {
        Matcher call = Pattern.compile(
            "\\b(?:(Client|Server)PlayNetworking\\s*\\.\\s*(registerGlobalReceiver|send)|new\\s+CustomPayload(?:S2C|C2S)Packet)\\s*\\("
        ).matcher(text);
        StringBuilder out = new StringBuilder();
        int copied = 0, line = 1, counted = 0;
        while (call.find()) {
            for (; counted < call.start(); counted++) if (text.charAt(counted) == '\n') line++;
            if ((lines != null && !lines.contains(line)) || call.start() < copied) continue;
            List<int[]> args = JavaText.arguments(text, call.end() - 1);
            String side = call.group(1), verb = call.group(2), replacement = null;
            int from = 0, to = 0;
            if (verb == null || verb.equals("send")) {
                // new CustomPayloadS2CPacket(channel, buf), ClientPlayNetworking.send(channel, buf), ServerPlayNetworking.send(player, channel, buf)
                int first = "Server".equals(side) ? 1 : 0;
                if (args.size() != first + 2) continue;
                from = args.get(first)[0];
                to = args.get(first + 1)[1];
                String inner = text.substring(from, to);
                int lead = inner.length() - inner.stripLeading().length();
                replacement = inner.substring(0, lead) + name + ".of(" + inner.strip() + ")";
            } else {
                if (args.size() != 2) continue;
                Matcher lambda = Pattern.compile(
                    "\\A(\\s*)\\(\\s*(\\w+)\\s*,\\s*(\\w+)\\s*,\\s*(\\w+)\\s*,\\s*(\\w+)(?:\\s*,\\s*(\\w+))?\\s*\\)\\s*->\\s*\\{"
                ).matcher(text.substring(args.get(1)[0], args.get(1)[1]));
                if (!lambda.find() || "Client".equals(side) != (lambda.group(6) == null)) continue;
                String declared = "Client".equals(side)
                    ? " var " +
                    lambda.group(2) +
                    " = mmt$context.client(); var " +
                    lambda.group(3) +
                    " = mmt$context.client().getNetworkHandler(); var " +
                    lambda.group(4) +
                    " = mmt$payload.buf(); var " +
                    lambda.group(5) +
                    " = mmt$context.responseSender();"
                    : " var " +
                    lambda.group(2) +
                    " = mmt$context.server(); var " +
                    lambda.group(3) +
                    " = mmt$context.player(); var " +
                    lambda.group(4) +
                    " = mmt$context.player().networkHandler; var " +
                    lambda.group(5) +
                    " = mmt$payload.buf(); var " +
                    lambda.group(6) +
                    " = mmt$context.responseSender();";
                from = args.get(0)[0];
                to = args.get(1)[0] + lambda.end();
                String channel = text.substring(from, args.get(0)[1]);
                int lead = channel.length() - channel.stripLeading().length();
                replacement =
                    channel.substring(0, lead) +
                    name +
                    ".type(" +
                    channel.strip() +
                    ")" +
                    text.substring(args.get(0)[1], args.get(1)[0]) +
                    lambda.group(1) +
                    "(mmt$payload, mmt$context) -> {" +
                    declared;
            }
            out.append(text, copied, from).append(replacement);
            copied = to;
        }
        return copied == 0 ? text : out.append(text.substring(copied)).toString();
    }

    /** Runs a "payload-bytes" rule: writes its class beside the first file it changes and imports it where it is used. */
    boolean applyPayloadBytes(Map<String, Object> rule, String errors) throws Exception {
        var apply = Json.object(rule.get("apply"));
        String name = Json.text(apply, "name", "MmtPacket");
        Map<Path, String> rewritten = new LinkedHashMap<>();
        for (var site : port.rules.reportedLines(errors, Pattern.compile(rule.get("match").toString())).entrySet()) {
            String original = Files.readString(site.getKey()), text = payloadCalls(
                original,
                name,
                site.getValue().keySet()
            );
            if (!text.equals(original)) rewritten.put(site.getKey(), text);
        }
        if (rewritten.isEmpty()) return false;
        Path first = rewritten.keySet().iterator().next();
        Matcher declared = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(rewritten.get(first));
        if (!declared.find()) return false;
        Path file = first.getParent().resolve(name + ".java");
        if (Files.exists(file) && !Files.readString(file).contains(Port.WRITTEN_MARK)) return false;
        if (!Files.exists(file)) {
            port.journal.track(file);
            Files.writeString(file, Json.text(apply, "source", "").replace("{package}", declared.group(1)));
        }
        for (var entry : rewritten.entrySet()) Files.writeString(
            entry.getKey(),
            port.rules.deferImports(
                entry.getKey(),
                Files.readString(entry.getKey()),
                JavaText.addImport(entry.getValue(), declared.group(1) + "." + name)
            )
        );
        return true;
    }
}
