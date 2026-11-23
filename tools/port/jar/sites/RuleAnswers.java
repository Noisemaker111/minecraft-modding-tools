import java.util.*;
import java.util.regex.*;
import org.objectweb.asm.*;

/** A reference nothing in the game's code answers, answered by a port rule or a stand-in (knowledge/fixes.json, knowledge/standins). */
final class RuleAnswers {

    final Sites sites;

    RuleAnswers(Sites sites) {
        this.sites = sites;
    }

    /**
     * The replacement a port rule gives for the old member (owners: the class the site names and the one declaring the
     * member), compiled into a private static method of this class that takes the old operands (opDesc, old names).
     * Returns {name, desc} of that method, or null when no rule names the member or its replacement does not link and
     * type here. args is the member's descriptor a rule's "(args)" is matched against (null for a field). With
     * receiver, the first operand is the receiver ($0).
     */
    String[] fix(List<String> owners, String name, String args, String opDesc, boolean receiver) {
        return fix(sites.port.fixes, owners, name, args, opDesc, receiver);
    }

    String[] fix(Map<String, List<JarPort.Fix>> book, List<String> owners, String name, String args, String opDesc, boolean receiver) {
        if (sites.mod.selfInterface && sites.mod.selfVersion < Opcodes.V1_8) {
            return null; // an interface before Java 8 holds no static method to write the rule in
        }
        Type[] ops = Type.getArgumentTypes(opDesc);
        Type ret = Type.getReturnType(opDesc);
        Type[] params = new Type[ops.length];
        for (int i = 0; i < ops.length; i++) {
            params[i] = Type.getType(sites.port.desc(ops[i].getDescriptor()));
            if (!sites.port.links.present(sites.mod, params[i])) {
                return null;
            }
        }
        Type newRet = Type.getType(sites.port.desc(ret.getDescriptor()));
        for (String owner : new LinkedHashSet<>(owners)) {
            for (JarPort.Fix f : book.getOrDefault(owner, List.of())) {
                Matcher n = f.name().matcher(name);
                if (!n.matches() || f.args() != null && (args == null || !args.startsWith(f.args())) || !holds(f)) {
                    continue;
                }
                try {
                    String expr = FixExpr.fill(f.expr(), n);
                    FixExpr.Node node = sites.port.ruleCode.parsed(expr);
                    if (RuleCode.dropsUnnamed(f, node, ops.length - (receiver ? 1 : 0))) {
                        continue;
                    }
                    Lowering low = new Lowering(sites.port, sites.mod, sites.callers, params, receiver, 0);
                    RuleCode.Code code = new RuleCode.Code();
                    Type got = low.lower(node, code);
                    if (ret.getSort() == Type.VOID) {
                        if (got.getSize() > 0) {
                            code.add(-got.getSize(), mv -> mv.visitInsn(got.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
                        }
                        code.add(0, mv -> mv.visitInsn(Opcodes.RETURN));
                    } else if (sites.port.links.present(sites.mod, newRet) && got.getSort() != Type.VOID && low.fits(got, newRet)) {
                        low.widen(got, newRet, code);
                        code.add(-newRet.getSize(), mv -> mv.visitInsn(newRet.getOpcode(Opcodes.IRETURN)));
                    } else {
                        continue;
                    }
                    String desc = Type.getMethodDescriptor(ret, ops);
                    RuleCode.Added m = sites.mod.added.computeIfAbsent(expr + desc, k -> new RuleCode.Added(
                        "mmt$fix$" + sites.mod.added.size() + "$" + Integer.toHexString(sites.mod.self.hashCode()), desc, code, low.locals
                    ));
                    return new String[] { m.name(), m.desc() };
                } catch (RuleCode.Refused | IllegalArgumentException notHere) {
                    // Another rule may still give one that links.
                }
            }
        }
        return null;
    }

    Map<Integer, Map<Integer, String>> constants; // call number -> argument (0 the first) -> the constant passed there

    /** A rule for one constant holds here when the call being written passes that constant as its operand. */
    boolean holds(JarPort.Fix f) {
        if (f.when() == null) {
            return true;
        }
        if (constants == null) {
            int dot = sites.key.indexOf('.'), paren = sites.key.indexOf('(');
            constants = paren < 0 ? Map.of() : Constants.of(sites.mod.bytes.get(sites.key.substring(0, dot)), sites.key.substring(dot + 1, paren), sites.key.substring(paren));
        }
        int colon = f.when().indexOf(':');
        int k = Integer.parseInt(f.when().substring(0, colon));
        return f.when().substring(colon + 1).equals(constants.getOrDefault(sites.callsSeen, Map.of()).get(k - 1));
    }

    /**
     * new X(args) where X's constructor does not link and a replacement gives a value of X some other way (1.21:
     * ResourceLocation.fromNamespaceAndPath for the closed constructor): the arguments are set aside, the two
     * references to the object not yet constructed that new and dup left are dropped, and the replacement is called.
     * Only where new was followed directly by dup, as javac and kotlinc write it.
     */
    boolean made(String owner, String declOwner, String desc) {
        String[] pending = sites.news.isEmpty() ? null : sites.news.peek();
        if (pending == null || !pending[0].equals(owner) || pending[1] == null) {
            return false;
        }
        String[] added = fix(List.of(owner, declOwner), "<init>", desc, desc.substring(0, desc.indexOf(')') + 1) + "L" + owner + ";", false);
        if (added == null) {
            return false;
        }
        sites.news.pop();
        Type[] args = Type.getArgumentTypes(desc);
        int[] slots = sites.spill(args, 0);
        sites.emitInsn(Opcodes.POP);
        sites.emitInsn(Opcodes.POP);
        for (int i = 0; i < args.length; i++) {
            sites.emitVarInsn(args[i].getOpcode(Opcodes.ILOAD), slots[i]);
        }
        sites.invokeAdded(added);
        return true;
    }
}
