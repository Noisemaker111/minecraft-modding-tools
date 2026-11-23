import java.util.*;
import java.util.regex.*;

/** Food properties the game moved to the consumable component. */
final class FoodMigration {

    /**
     * Fabric's 1.20.5 networking took away channel-plus-buffer packets. On the lines javac reported, each old call is
     * carried over to a payload that holds the same bytes (the port writes the class, named here): a send's channel and
     * buffer become name.of(channel, buf), a receiver's channel becomes name.type(channel), and its old lambda
     * (client, handler, buf, responseSender) or (server, player, handler, buf, responseSender) gets the new
     * (payload, context) one with the old names declared first, so its body runs unchanged. Every line when lines is null.
     */
    /**
     * A method that now takes a different argument type, written the same way for every argument: in each statement that
     * starts with apply.statement (a regex), every top-level argument of a call to one of apply.calls, across lines, is
     * rewritten by apply.wrap ($0 is the argument), unless it already matches apply.skip. 26.2's TagAppender.add takes a
     * ResourceKey: tag(T).add(BLOCK_A, BLOCK_B.get()) -> add(BLOCK_A.builtInRegistryHolder().key(), ...).
     */
    /**
     * 1.21.2 took eating from FoodProperties: its builder lost effect(effect, chance) and fast(), which now describe the
     * item's consumable (Consumables.defaultFood().consumeSeconds(0.8F).onConsume(new ApplyStatusEffectsConsumeEffect(
     * effect, chance))), set with Item.Properties.food(food, consumable). Each builder chain that used them keeps the rest
     * and is passed through mmtFood, which remembers that food's consumable by identity; foodUses makes every
     * Item.Properties.food(food) in the mod set it, so a food handed through the mod's own helpers keeps its effects.
     * Lines are kept: the helpers go at the end of the class.
     */
    static String foodConsumables(String text) {
        Matcher chain = Pattern.compile("(\\(\\s*)?new\\s+(?:net\\.minecraft\\.world\\.food\\.)?FoodProperties\\s*\\.\\s*Builder\\s*\\(\\s*\\)(\\s*\\))?").matcher(text);
        StringBuilder out = new StringBuilder();
        int done = 0;
        while (chain.find()) {
            if (chain.start() < done) continue;
            // (new FoodProperties.Builder()) is the chain's start; a lone '(' or ')' belongs to the code around it.
            boolean wrapped = chain.group(1) != null && chain.group(2) != null;
            int start = chain.group(1) != null && !wrapped ? chain.start() + chain.group(1).length() : chain.start();
            int at = chain.group(2) != null && !wrapped ? chain.end() - chain.group(2).length() : chain.end();
            StringBuilder kept = new StringBuilder(text.substring(start, at));
            StringBuilder consumable = new StringBuilder("net.minecraft.world.item.component.Consumables.defaultFood()");
            boolean changed = false, built = false;
            Matcher call = Pattern.compile("\\G(\\s*)\\.\\s*(\\w+)\\s*\\(").matcher(text);
            while (!built && call.find(at)) {
                int open = call.end() - 1, close = JavaText.closingParen(text, open);
                if (close < 0) break;
                String name = call.group(2), args = text.substring(open + 1, close);
                at = close + 1;
                List<String> parts = JavaText.topLevelArguments(args);
                // A dropped call keeps its line breaks, so javac's line numbers hold.
                String breaks = call.group(1).replaceAll("[^\\n]", "") + args.replaceAll("[^\\n]", "");
                if (name.equals("fast") && args.isBlank()) {
                    consumable.append(".consumeSeconds(0.8F)");
                    kept.append(breaks);
                    changed = true;
                } else if (name.equals("effect") && parts.size() == 2) {
                    consumable.append(".onConsume(new net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect(mmtEffect(")
                        .append(parts.get(0).strip()).append("), ").append(parts.get(1).strip()).append("))");
                    kept.append(breaks);
                    changed = true;
                } else {
                    kept.append(text, call.start(), at);
                    built = name.equals("build");
                }
            }
            if (!changed || !built) continue;
            out.append(text, done, start).append("mmtFood(").append(kept).append(", ").append(consumable).append(".build())");
            done = at;
        }
        if (done == 0) return text;
        String result = out.append(text.substring(done)).toString();
        Matcher owner = Pattern.compile("\\b(?:class|interface|enum|record)\\s+\\w+[^{]*\\{").matcher(result);
        if (!owner.find()) return text;
        int close = JavaText.closingBrace(result, owner.end() - 1);
        if (close < 0) return text;
        String food = "net.minecraft.world.food.FoodProperties", item = "net.minecraft.world.item.component.Consumable",
            effect = "net.minecraft.world.effect.MobEffectInstance";
        String helpers =
            "\n    /** Written by mmt port: how each food was eaten before 1.21.2 moved eating to the item's consumable. */\n" +
            "    private static " + food + " mmtFood(" + food + " food, " + item + " consumable) {\n" +
            "        MmtFoods.CONSUMABLES.put(food, consumable);\n        return food;\n    }\n\n" +
            "    public static " + item + " mmtConsumable(" + food + " food) {\n" +
            "        return MmtFoods.CONSUMABLES.getOrDefault(food, net.minecraft.world.item.component.Consumables.DEFAULT_FOOD);\n    }\n\n" +
            "    private static " + effect + " mmtEffect(" + effect + " effect) {\n        return effect;\n    }\n\n" +
            "    private static " + effect + " mmtEffect(java.util.function.Supplier<" + effect + "> effect) {\n        return effect.get();\n    }\n\n" +
            "    private static final class MmtFoods {\n" +
            "        static final java.util.Map<" + food + ", " + item + "> CONSUMABLES = new java.util.IdentityHashMap<>();\n    }\n";
        return result.substring(0, close) + helpers + result.substring(close);
    }

    /** The qualified name of the class foodConsumables gave its helpers. */
    static String foodOwner(String text) {
        Matcher pkg = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(text);
        Matcher owner = Pattern.compile("\\b(?:class|interface|enum|record)\\s+(\\w+)").matcher(text);
        return (pkg.find() ? pkg.group(1) + "." : "") + (owner.find() ? owner.group(1) : "");
    }

    /** Item.Properties.food(food) also sets the consumable that food's builder described (Consumables.DEFAULT_FOOD otherwise, as food(food) does). */
    static String foodUses(String text, String owner) {
        if (!text.contains("Properties")) return text;
        Matcher use = Pattern.compile("\\.\\s*food\\s*\\(").matcher(text);
        StringBuilder out = new StringBuilder();
        int done = 0;
        while (use.find()) {
            int open = use.end() - 1, close = JavaText.closingParen(text, open);
            if (close < 0 || open < done) continue;
            List<String> args = JavaText.topLevelArguments(text.substring(open + 1, close));
            if (args.size() != 1 || args.get(0).isBlank() || text.startsWith(".component(net.minecraft.core.component.DataComponents.CONSUMABLE", close + 1)) continue;
            out.append(text, done, close + 1).append(".component(net.minecraft.core.component.DataComponents.CONSUMABLE, ").append(owner)
                .append(".mmtConsumable(").append(args.get(0).strip()).append("))");
            done = close + 1;
        }
        return done == 0 ? text : out.append(text.substring(done)).toString();
    }

    /**
     * 1.21.2 moved a food's eating effects from FoodProperties.effects() (PossibleEffect: effect(), probability()) to the
     * stack's consumable component (ApplyStatusEffectsConsumeEffect: effects(), probability()). The old list is rebuilt
     * from the stack the food came from: stack.get(DataComponents.FOOD).effects(), stack.getFoodProperties(e).effects(),
     * and a FoodProperties variable declared from either. PossibleEffect becomes the class's own record with the same
     * accessors, so loops over the list read as before. A food not traced to its stack is left to the compiler.
     */
    static String foodEffects(String text) {
        String stackExpr = "([\\w$]+(?:\\.[\\w$]+(?:\\(\\))?)*)";
        String fromStack = "\\.(?:get\\s*\\(\\s*(?:net\\.minecraft\\.core\\.component\\.)?DataComponents\\.FOOD\\s*\\)|getFoodProperties\\s*\\([^()]*\\))";
        String out = text.replaceAll(stackExpr + fromStack + "\\s*\\.\\s*effects\\s*\\(\\s*\\)", "mmtFoodEffects($1)");
        Matcher declared = Pattern.compile("\\bFoodProperties\\s+(\\w+)\\s*=\\s*" + stackExpr + fromStack + "\\s*;").matcher(out);
        StringBuilder sb = new StringBuilder();
        int done = 0;
        while (declared.find()) {
            // The variable's uses up to the end of the block it is declared in.
            int depth = 0, end = declared.end();
            while (end < out.length() && depth >= 0) {
                char ch = out.charAt(end);
                depth += ch == '{' ? 1 : ch == '}' ? -1 : 0;
                end++;
            }
            if (declared.end() < done) continue;
            sb.append(out, done, declared.end());
            String uses = "\\b" + declared.group(1) + "\\s*\\.\\s*effects\\s*\\(\\s*\\)";
            String stack = Matcher.quoteReplacement(declared.group(2));
            sb.append(out.substring(declared.end(), end).replaceAll(uses, "mmtFoodEffects(" + stack + ")"));
            done = end;
        }
        out = sb.append(out.substring(done)).toString();
        if (out.equals(text)) return text;
        out = out.replaceAll("\\b(?:net\\.minecraft\\.world\\.food\\.)?FoodProperties\\s*\\.\\s*PossibleEffect\\b", "MmtPossibleEffect");
        int close = out.lastIndexOf('}');
        String helper =
            "\n    /** Written by mmt port: 1.21.2 moved a food's effects to its consumable component. The same list as before: each effect with its chance. */\n" +
            "    private static java.util.List<MmtPossibleEffect> mmtFoodEffects(net.minecraft.world.item.ItemStack stack) {\n" +
            "        java.util.List<MmtPossibleEffect> effects = new java.util.ArrayList<>();\n" +
            "        net.minecraft.world.item.component.Consumable consumable = stack.get(net.minecraft.core.component.DataComponents.CONSUMABLE);\n" +
            "        if (consumable == null) return effects;\n" +
            "        for (net.minecraft.world.item.consume_effects.ConsumeEffect effect : consumable.onConsumeEffects())\n" +
            "            if (effect instanceof net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect apply)\n" +
            "                for (net.minecraft.world.effect.MobEffectInstance instance : apply.effects()) effects.add(new MmtPossibleEffect(instance, apply.probability()));\n" +
            "        return effects;\n    }\n\n" +
            "    /** What FoodProperties.PossibleEffect was before 1.21.2: an effect and its chance. */\n" +
            "    private record MmtPossibleEffect(net.minecraft.world.effect.MobEffectInstance effect, float probability) {}\n";
        return out.substring(0, close) + helper + out.substring(close);
    }
}
