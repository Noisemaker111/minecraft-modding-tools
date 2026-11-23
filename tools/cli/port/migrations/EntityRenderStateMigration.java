import java.util.*;
import java.util.regex.*;

/** Entity renderers and models the game moved to render states. */
final class EntityRenderStateMigration {

    /**
     * 1.21.2's models pose from a render state: EntityModel<T extends Entity>.setupAnim(entity, limbSwing, limbSwingAmount,
     * ageInTicks, netHeadYaw, headPitch) became EntityModel<S extends EntityRenderState>.setupAnim(S). The renderer that
     * computed those five values in 1.21.1 (LivingEntityRenderer.render) now stores the same ones in the state
     * (walkAnimationPos, walkAnimationSpeed, ageInTicks, yRot, xRot). A mod model extending a game model XModel<..> takes
     * XRenderState, and its setupAnim keeps its body with the old names read back from the state; super.setupAnim passes
     * the state. The entity itself is not in the state: a body that still reads it is left for javac to name.
     */
    static String entityModelRenderState(String text) {
        Matcher cls = Pattern.compile("\\bclass\\s+\\w+\\s*(?:<[^{]*?>)?\\s+extends\\s+(\\w*)Model\\s*<\\s*([^<>{]+?)\\s*>").matcher(text);
        if (!cls.find()) return text;
        String prefix = cls.group(1).isEmpty() ? "Entity" : cls.group(1);
        String state = prefix + "RenderState";
        boolean living = !prefix.equals("Entity");
        Matcher setup = Pattern.compile(
            "(public\\s+void\\s+setupAnim\\s*\\()\\s*[\\w.<>?, ]+?\\s+(\\w+)\\s*,\\s*float\\s+(\\w+)\\s*,\\s*float\\s+(\\w+)\\s*,\\s*float\\s+(\\w+)\\s*,\\s*float\\s+(\\w+)\\s*,\\s*float\\s+(\\w+)\\s*\\)\\s*\\{"
        ).matcher(text);
        if (!setup.find() || !living) return text;
        String var = Pattern.compile("\\bstate\\b").matcher(text).find() ? "mmtState" : "state";
        int bodyOpen = setup.end() - 1, bodyClose = JavaText.closingBrace(text, bodyOpen);
        if (bodyClose < 0) return text;
        String indent = text.substring(text.lastIndexOf('\n', setup.start()) + 1, setup.start()).replaceAll("\\S.*", "") + "    ";
        String[] names = { setup.group(3), setup.group(4), setup.group(5), setup.group(6), setup.group(7) };
        String[] fields = { "walkAnimationPos", "walkAnimationSpeed", "ageInTicks", "yRot", "xRot" };
        StringBuilder prologue = new StringBuilder();
        for (int i = 0; i < 5; i++) prologue.append('\n').append(indent).append("float ").append(names[i]).append(" = ").append(var).append('.').append(fields[i]).append(';');
        String body = text.substring(bodyOpen + 1, bodyClose).replaceAll(
            "\\bsuper\\s*\\.\\s*setupAnim\\s*\\(\\s*" + Pattern.quote(setup.group(2)) + "\\s*,[^;]*\\)\\s*;",
            Matcher.quoteReplacement("super.setupAnim(" + var + ");")
        );
        String out = text.substring(0, setup.start()) + setup.group(1) + state + " " + var + ") {" + prologue + body + text.substring(bodyClose);
        out = out.substring(0, cls.start()) + text.substring(cls.start(), cls.end()).replaceFirst(
            "Model\\s*<\\s*" + Pattern.quote(cls.group(2)) + "\\s*>$",
            Matcher.quoteReplacement("Model<" + state + ">")
        ) + out.substring(cls.end());
        return JavaText.addImport(out, "net.minecraft.client.renderer.entity.state." + state);
    }

    /**
     * 1.21.2 split entity rendering into extracting a render state and drawing it: EntityRenderer<T> became
     * EntityRenderer<T, S extends EntityRenderState>, and render(T entity, float yaw, float partialTicks, PoseStack,
     * MultiBufferSource, int) became render(S state, PoseStack, MultiBufferSource, int). A renderer that extends
     * EntityRenderer<E> directly keeps its drawing code unchanged behind a state of its own that carries what the old
     * method was given: the entity, the partial tick, and the yaw the 1.21.1 dispatcher computed
     * (Mth.lerp(partialTick, yRotO, getYRot())). The old parameter names are read back from it at the top of render, and
     * super.render passes the state. getTextureLocation(E) stays as a plain method: EntityRenderer has none any more.
     * Another renderer's render(entity, yaw, partialTicks, ...) is drawn through createRenderState(entity, partialTicks).
     * A generic renderer (class R<T> extends EntityRenderer<T>) is left to the compiler.
     */
    static String entityRenderState(String text) {
        Matcher cls = Pattern.compile("\\bclass\\s+(\\w+)\\s+extends\\s+EntityRenderer\\s*<\\s*([\\w.]+)\\s*>").matcher(text);
        if (!cls.find()) return text;
        String renderer = cls.group(1), entity = cls.group(2);
        int open = text.indexOf('{', cls.end());
        int close = open < 0 ? -1 : JavaText.closingBrace(text, open);
        if (close < 0) return text;
        String body = text.substring(open + 1, close);
        Matcher render = Pattern.compile(
            "((?:@Override\\s+)?public\\s+void\\s+render\\s*)\\(\\s*(?:final\\s+)?" + Pattern.quote(entity) +
            "\\s+(\\w+)\\s*,\\s*(?:final\\s+)?float\\s+(\\w+)\\s*,\\s*(?:final\\s+)?float\\s+(\\w+)\\s*,\\s*" +
            "((?:final\\s+)?PoseStack\\s+(\\w+)\\s*,\\s*(?:final\\s+)?MultiBufferSource\\s+(\\w+)\\s*,\\s*(?:final\\s+)?int\\s+(\\w+))\\s*\\)\\s*\\{"
        ).matcher(body);
        if (!render.find()) return text;
        String entityName = render.group(2), yawName = render.group(3), tickName = render.group(4);
        int renderClose = JavaText.closingBrace(body, render.end() - 1);
        if (renderClose < 0) return text;
        String renderBody = body.substring(render.end(), renderClose);
        // super.render(entity, yaw, partialTicks, poseStack, buffer, light) -> super.render(state, poseStack, buffer, light)
        StringBuilder sb = new StringBuilder();
        Matcher superRender = Pattern.compile("\\bsuper\\s*\\.\\s*render\\s*\\(").matcher(renderBody);
        int done = 0;
        while (superRender.find()) {
            int callOpen = superRender.end() - 1, callClose = JavaText.closingParen(renderBody, callOpen);
            if (callClose < 0) break;
            List<String> args = JavaText.topLevelArguments(renderBody.substring(callOpen + 1, callClose));
            if (args.size() != 6) continue;
            sb.append(renderBody, done, callOpen + 1).append("state, ").append(args.get(3).strip()).append(", ").append(args.get(4).strip())
                .append(", ").append(args.get(5).strip()).append(')');
            done = callClose + 1;
        }
        sb.append(renderBody.substring(done));
        String indent = "        ";
        String newRender =
            render.group(1) + "(MmtState state, " + render.group(5) + ") {\n" +
            indent + entity + " " + entityName + " = state.mmtEntity;\n" +
            indent + "float " + yawName + " = state.mmtYaw;\n" +
            indent + "float " + tickName + " = state.mmtPartialTick;" +
            sb;
        String added =
            "\n\n    @Override\n    public MmtState createRenderState() {\n        return new MmtState();\n    }\n\n" +
            "    @Override\n    public void extractRenderState(" + entity + " entity, MmtState state, float partialTick) {\n" +
            "        super.extractRenderState(entity, state, partialTick);\n" +
            "        state.mmtEntity = entity;\n        state.mmtPartialTick = partialTick;\n" +
            "        state.mmtYaw = net.minecraft.util.Mth.lerp(partialTick, entity.yRotO, entity.getYRot());\n    }\n\n" +
            "    /** What render was given before 1.21.2 split it into extracting and drawing a render state. */\n" +
            "    public static class MmtState extends EntityRenderState {\n        public " + entity + " mmtEntity;\n" +
            "        public float mmtPartialTick;\n        public float mmtYaw;\n    }\n";
        String newBody = body.substring(0, render.start()) + newRender + body.substring(renderClose) ;
        // EntityRenderer no longer declares getTextureLocation: the mod's stays as a plain method.
        newBody = newBody.replaceAll(
            "(?m)^([ \\t]*)@Override\\s+(public\\s+[\\w.]+\\s+getTextureLocation\\s*\\(\\s*" + Pattern.quote(entity) + "\\b)",
            "$1$2"
        );
        // Another renderer drawn with an entity: through its own render state.
        Matcher delegate = Pattern.compile("([\\w.]+(?:\\([^()]*\\))?)\\s*\\.\\s*render\\s*\\(").matcher(newBody);
        StringBuilder withDelegates = new StringBuilder();
        done = 0;
        while (delegate.find()) {
            if (delegate.group(1).equals("super")) continue;
            int callOpen = delegate.end() - 1, callClose = JavaText.closingParen(newBody, callOpen);
            if (callClose < 0) continue;
            List<String> args = JavaText.topLevelArguments(newBody.substring(callOpen + 1, callClose));
            if (args.size() != 6) continue;
            // getRenderer(entity) is an EntityRenderer<? super T, ?>: its own state only goes back in through the raw type.
            String target = "((net.minecraft.client.renderer.entity.EntityRenderer) " + delegate.group(1) + ")";
            withDelegates.append(newBody, done, delegate.start()).append(target).append(".render(").append(target).append(".createRenderState(").append(args.get(0).strip())
                .append(", ").append(args.get(2).strip()).append("), ").append(args.get(3).strip()).append(", ").append(args.get(4).strip())
                .append(", ").append(args.get(5).strip()).append(')');
            done = callClose + 1;
        }
        withDelegates.append(newBody.substring(done));
        String header = text.substring(0, cls.start()) + text.substring(cls.start(), cls.end()).replaceFirst(
            "EntityRenderer\\s*<\\s*" + Pattern.quote(entity) + "\\s*>",
            Matcher.quoteReplacement("EntityRenderer<" + entity + ", " + renderer + ".MmtState>")
        );
        String out = header + text.substring(cls.end(), open + 1) + withDelegates + added + text.substring(close);
        return JavaText.addImport(out, "net.minecraft.client.renderer.entity.state.EntityRenderState");
    }
}
