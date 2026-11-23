import java.util.*;
import org.objectweb.asm.*;

/**
 * A loader's published rename list links what its own code cannot. Fabric API renamed classes and members to Mojang's
 * names for 26.1 (keybinding.v1.KeyBindingHelper.registerKeyBinding -> keymapping.v1.KeyMappingHelper.registerKeyMapping,
 * ServerTickEvents.START_WORLD_TICK -> START_LEVEL_TICK) and published them as an IntelliJ migration map of the classes
 * and a porting page listing each class's members. Here a two-release loader is built whose renamed classes share no
 * name and no code with the old ones: linking alone leaves them apart, and the list read in Fabric's own formats links
 * the class, the method (its descriptor read through the renamed class) and the field. A member the list renames in a
 * class it does not rename (relative name on the page) is linked too, and so is a rename whose old name another renamed
 * class took (FabricServerRecipeManager -> FabricRecipeManager, whose old class became FabricRecipeAccess).
 *
 *   java -cp asm.jar:CLASSES GamePublishedTest   (in the jar-port check of bench/run_tests.py)
 */
final class GamePublishedTest {

    static final String OLD = "net/fabricmc/fabric/api/client/keybinding/v1/KeyBindingHelper";
    static final String NEW = "net/fabricmc/fabric/api/client/keymapping/v1/KeyMappingHelper";
    static final String TICKS = "net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents";
    static final String RECIPES = "net/fabricmc/fabric/api/recipe/v1/";

    static final String CLASS_MAP = """
        <?xml version="1.0" encoding="UTF-8"?>
        <migrationMap>
        	<entry oldName="net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper" newName="net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper" type="class"/>
        	<entry oldName="net.fabricmc.fabric.api.client.keybinding.v1.package-info" newName="net.fabricmc.fabric.api.client.keymapping.v1.package-info" type="class"/>
        </migrationMap>
        """;

    static final String PAGE = """
        ## List Of Renames {#renames}

        - `net/fabricmc/fabric/api/client/keybinding/v1/KeyBindingHelper` → `net/fabricmc/fabric/api/client/keymapping/v1/KeyMappingHelper`
          - `registerKeyBinding` → `registerKeyMapping`

        - `net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents`
          - `START_WORLD_TICK` → `START_LEVEL_TICK`
          - `startWorld` → `startLevel`
        """;

    public static void main(String[] args) throws Exception {
        Map<String, String> classes = new LinkedHashMap<>();
        Map<String, Map<String, String>> members = new LinkedHashMap<>();
        GameDiff.Published.parse(CLASS_MAP, PAGE, classes, members);
        JarPortTest.expect(NEW.equals(classes.get(OLD)), "the class map's entry is read in internal names: " + classes);
        JarPortTest.expect("registerKeyMapping".equals(members.getOrDefault(OLD, Map.of()).get("registerKeyBinding")), "the page's member is read under its old class: " + members);
        JarPortTest.expect("START_LEVEL_TICK".equals(members.getOrDefault(TICKS, Map.of()).get("START_WORLD_TICK")), "a member of a class kept is read: " + members);

        GameHop alone = hop(GameDiff.Published.NONE);
        JarPortTest.expect(!linked(alone, OLD, NEW), "without the list the renamed class is not linked (the test would prove nothing)");

        GameHop hop = hop(new GameDiff.Published(classes, members));
        JarPortTest.expect(linked(hop, OLD, NEW), "the published class rename is linked");
        String method = hop.mAB.entrySet().stream().filter(e -> e.getKey().name.equals("registerKeyBinding")).map(e -> e.getValue().owner.name + "." + e.getValue().name)
            .findFirst().orElse("none");
        JarPortTest.expect(method.equals(NEW + ".registerKeyMapping"), "the published method rename is linked: " + method);
        String field = hop.fAB.entrySet().stream().filter(e -> e.getKey().name.equals("START_WORLD_TICK")).map(e -> e.getValue().name).findFirst().orElse("none");
        JarPortTest.expect(field.equals("START_LEVEL_TICK"), "the published field rename is linked: " + field);
        String kept = hop.mAB.entrySet().stream().filter(e -> e.getKey().name.equals("startWorld")).map(e -> e.getValue().name).findFirst().orElse("none");
        JarPortTest.expect(kept.equals("startLevel"), "a member renamed in a class that kept its name is linked: " + kept);

        // one rename's old name is another's new name: the name alone would pair the two FabricRecipeManagers
        Map<String, String> chained = new LinkedHashMap<>();
        chained.put(RECIPES + "FabricRecipeManager", RECIPES + "FabricRecipeAccess");
        chained.put(RECIPES + "FabricServerRecipeManager", RECIPES + "FabricRecipeManager");
        GameHop chain = new GameHop(GameDiff.read("1.0", List.of(), null, List.of(type("FabricRecipeManager", "getAllMatches"), type("FabricServerRecipeManager", "getAllOfType"))),
            GameDiff.read("2.0", List.of(), null, List.of(type("FabricRecipeAccess", "getAllMatches"), type("FabricRecipeManager", "getAllOfType"))));
        chain.published = new GameDiff.Published(chained, Map.of());
        chain.run();
        JarPortTest.expect(linked(chain, RECIPES + "FabricRecipeManager", RECIPES + "FabricRecipeAccess"), "a renamed class whose old name another class took is linked to its new name");
        JarPortTest.expect(linked(chain, RECIPES + "FabricServerRecipeManager", RECIPES + "FabricRecipeManager"), "the class that took the old name is linked to it");
        if (JarPortTest.failures > 0) {
            System.exit(1);
        }
        System.out.println("game-published: 10 checks passed");
    }

    static GameHop hop(GameDiff.Published published) {
        GameDiff.Snap a = GameDiff.read("1.0", List.of(), null, loader(false));
        GameDiff.Snap b = GameDiff.read("2.0", List.of(), null, loader(true));
        GameHop hop = new GameHop(a, b);
        hop.published = published;
        hop.run();
        return hop;
    }

    static boolean linked(GameHop hop, String from, String to) {
        return hop.cAB.entrySet().stream().anyMatch(e -> e.getKey().name.equals(from) && e.getValue().name.equals(to));
    }

    /** An interface in the recipe package with one method of its own. */
    static byte[] type(String name, String method) {
        ClassWriter w = new ClassWriter(0);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, RECIPES + name, null, "java/lang/Object", null);
        w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, method, "()Ljava/util/List;", null, null).visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** The old loader, or the new one: the helper renamed with its method, and the tick events with a field and a method renamed. */
    static List<byte[]> loader(boolean renamed) {
        ClassWriter h = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        h.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, renamed ? NEW : OLD, null, "java/lang/Object", null);
        MethodVisitor m = h.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, renamed ? "registerKeyMapping" : "registerKeyBinding", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        m.visitCode();
        if (renamed) {
            // a body of its own: the new helper sorts and checks what it registers, the old one only handed it back
            m.visitLdcInsn("category");
            m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNull", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            m.visitInsn(Opcodes.POP);
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNull", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        } else {
            m.visitVarInsn(Opcodes.ALOAD, 0);
        }
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        h.visitEnd();
        ClassWriter t = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        t.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, TICKS, null, "java/lang/Object", null);
        t.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, renamed ? "START_LEVEL_TICK" : "START_WORLD_TICK", "Ljava/lang/Object;", null, null).visitEnd();
        t.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, renamed ? "END_LEVEL_TICK" : "END_WORLD_TICK", "Ljava/lang/Object;", null, null).visitEnd();
        MethodVisitor s = t.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, renamed ? "startLevel" : "startWorld", "()I", null, null);
        s.visitCode();
        s.visitLdcInsn(renamed ? "level" : "world");
        s.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", renamed ? "hashCode" : "length", "()I", false);
        s.visitInsn(Opcodes.IRETURN);
        s.visitMaxs(0, 0);
        s.visitEnd();
        MethodVisitor e = t.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, renamed ? "endLevel" : "endWorld", "()J", null, null);
        e.visitCode();
        e.visitInsn(Opcodes.LCONST_1);
        e.visitInsn(Opcodes.LRETURN);
        e.visitMaxs(0, 0);
        e.visitEnd();
        t.visitEnd();
        return List.of(h.toByteArray(), t.toByteArray());
    }
}
