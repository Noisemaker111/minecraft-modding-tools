import java.util.*;
import org.objectweb.asm.*;

/** A port rule's replacement expression (knowledge/fixes.json) as bytecode: parsed, checked against the new release, and added to the class. */
final class RuleCode {

    final JarPort port;

    RuleCode(JarPort port) {
        this.port = port;
    }

    /** A private static method added to the class being rewritten: a rule's replacement, taking the old member's operands. */
    record Added(String name, String desc, Code code, int locals) {}

    /** The type of null, which fits any reference. */
    static final Type NULL = Type.getObjectType("null");

    /** Code being built: each step writes to a method, and the stack it needs is counted as it goes. */
    static final class Code {
        final List<java.util.function.Consumer<MethodVisitor>> steps = new ArrayList<>();
        int depth;
        int max;

        void add(int change, java.util.function.Consumer<MethodVisitor> step) {
            steps.add(step);
            depth += change;
            max = Math.max(max, depth);
        }

        void append(Code other) {
            steps.addAll(other.steps);
            max = Math.max(max, depth + other.max);
            depth += other.depth;
        }
    }

    /** A replacement that does not link or type in the new release; it is not written. */
    static final class Refused extends RuntimeException {
        Refused(String why) {
            super(why, null, false, false);
        }
    }

    final Map<String, FixExpr.Node> parsed = new HashMap<>(); // a rule's expression, filled, once parsed

    /**
     * Whether a port answer (a learned replacement or a rule's inClass, FixExpr) compiles against the new release for a use
     * whose operands have opDesc's types (old names; the receiver first when receiver), as Sites.fix lowers it into a class:
     * a source port (SourceAnswers) writes an answer only when a jar port of the same use would.
     */
    boolean answerLinks(String expr, String opDesc, boolean receiver) {
        Type[] ops = Type.getArgumentTypes(opDesc);
        Type ret = Type.getReturnType(opDesc);
        JarPort.Mod mod = new JarPort.Mod();
        Type[] params = new Type[ops.length];
        for (int i = 0; i < ops.length; i++) {
            params[i] = Type.getType(port.desc(ops[i].getDescriptor()));
            if (!port.links.present(mod, params[i])) {
                return false;
            }
        }
        Type newRet = Type.getType(port.desc(ret.getDescriptor()));
        try {
            Lowering low = new Lowering(port, mod, List.of(), params, receiver, 0);
            Type got = low.lower(parsed(expr), new Code());
            return ret.getSort() == Type.VOID || got.getSort() != Type.VOID && port.links.present(mod, newRet) && low.fits(got, newRet);
        } catch (RuntimeException refused) {
            return false;
        }
    }

    FixExpr.Node parsed(String expr) {
        FixExpr.Node n = parsed.get(expr);
        if (n == null) {
            n = FixExpr.parse(expr);
            parsed.put(expr, n);
        }
        return n;
    }

    /**
     * An argument left out is a decision about one overload, so only a rule naming that overload's arguments may drop
     * one: spawnAtLocation(stack) -> ...($1) must not also take spawnAtLocation(stack, offset).
     */
    static boolean dropsUnnamed(JarPort.Fix f, FixExpr.Node node, int arguments) {
        Set<Integer> used = FixExpr.operands(node);
        for (int i = 1; i <= arguments; i++) {
            if (!used.contains(i)) {
                return f.args() == null;
            }
        }
        return false;
    }

    static final Map<Integer, Set<Integer>> WIDER = Map.of(
        Type.BYTE, Set.of(Type.SHORT, Type.INT, Type.LONG, Type.FLOAT, Type.DOUBLE),
        Type.SHORT, Set.of(Type.INT, Type.LONG, Type.FLOAT, Type.DOUBLE),
        Type.CHAR, Set.of(Type.INT, Type.LONG, Type.FLOAT, Type.DOUBLE),
        Type.INT, Set.of(Type.LONG, Type.FLOAT, Type.DOUBLE),
        Type.LONG, Set.of(Type.FLOAT, Type.DOUBLE),
        Type.FLOAT, Set.of(Type.DOUBLE)
    );

    static final Map<String, Integer> CONVERSIONS = Map.ofEntries(
        Map.entry("IJ", Opcodes.I2L), Map.entry("IF", Opcodes.I2F), Map.entry("ID", Opcodes.I2D),
        Map.entry("JI", Opcodes.L2I), Map.entry("JF", Opcodes.L2F), Map.entry("JD", Opcodes.L2D),
        Map.entry("FI", Opcodes.F2I), Map.entry("FJ", Opcodes.F2L), Map.entry("FD", Opcodes.F2D),
        Map.entry("DI", Opcodes.D2I), Map.entry("DJ", Opcodes.D2L), Map.entry("DF", Opcodes.D2F)
    );
}
