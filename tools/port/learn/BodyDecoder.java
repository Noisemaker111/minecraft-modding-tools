import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.*;
import java.util.*;

/** Stack machine over one method's bytecode: builds each value as a tree of what it is computed from. */
final class BodyDecoder {

    final GameDiff.Origin o;
    final String self;
    final GameDiff.Body body = new GameDiff.Body();
    final Map<Integer, GameDiff.Node> locals = new HashMap<>();
    final Map<Label, State> saved = new HashMap<>();
    List<GameDiff.Node> stack = new ArrayList<>();
    boolean live = true;
    int index = -1; // the instruction being read, counted from 0
    final Set<Integer> starts = new HashSet<>(); // instructions a jump or a handler reaches
    final Set<Integer> jumps = new HashSet<>(); // instructions that leave the straight line: jumps, switches, returns, throws

    BodyDecoder(GameDiff.Origin o, String self) {
        this.o = o;
        this.self = self;
    }

    static String kind(TypeKind k) {
        return k.upperBound().descriptorString();
    }

    static boolean wide(GameDiff.Node n) {
        return n.type.equals("J") || n.type.equals("D");
    }

    GameDiff.Node pop() {
        if (stack.isEmpty()) {
            throw new IllegalStateException("stack underflow");
        }
        return stack.remove(stack.size() - 1);
    }

    GameDiff.Node[] pop(int n) {
        GameDiff.Node[] out = new GameDiff.Node[n];
        for (int i = n - 1; i >= 0; i--) {
            out[i] = pop();
        }
        return out;
    }

    void push(GameDiff.Node n) {
        if (n.at < 0 && "svwgf".indexOf(n.op) >= 0) {
            n.at = index;
        }
        stack.add(n);
    }

    GameDiff.Node statement(String kind, GameDiff.Ref ref, GameDiff.Node... kids) {
        GameDiff.Node s = new GameDiff.Node('S', ref, kind, "V", kids);
        s.at = index;
        body.statements.add(s);
        return s;
    }

    /** The operand stack and locals a jump carries to its target. */
    record State(List<GameDiff.Node> stack, Map<Integer, GameDiff.Node> locals) {}

    void save(Label l) {
        State have = saved.get(l);
        saved.put(l, have == null ? new State(new ArrayList<>(stack), new HashMap<>(locals)) : new State(merge(have.stack(), stack), merge(have.locals(), locals)));
    }

    /** A value that depends on which way control came: it stands for neither, and Learner never matches it. */
    static GameDiff.Node phi(String type) {
        return new GameDiff.Node('o', null, "phi", type);
    }

    static boolean phi(GameDiff.Node n) {
        return n.op == 'o' && "phi".equals(n.value);
    }

    static List<GameDiff.Node> merge(List<GameDiff.Node> x, List<GameDiff.Node> y) {
        if (x.size() != y.size()) {
            throw new IllegalStateException("stack heights differ");
        }
        List<GameDiff.Node> out = new ArrayList<>();
        for (int i = 0; i < x.size(); i++) {
            out.add(x.get(i) == y.get(i) ? x.get(i) : phi(x.get(i).type));
        }
        return out;
    }

    static Map<Integer, GameDiff.Node> merge(Map<Integer, GameDiff.Node> x, Map<Integer, GameDiff.Node> y) {
        Map<Integer, GameDiff.Node> out = new HashMap<>();
        Set<Integer> slots = new HashSet<>(x.keySet());
        slots.addAll(y.keySet());
        for (int s : slots) {
            GameDiff.Node a = x.get(s), b = y.get(s);
            out.put(s, a == b ? a : phi(a != null ? a.type : b.type));
        }
        return out;
    }

    GameDiff.Ref member(char kind, String owner, String name, String desc) {
        return GameDiff.resolve(new GameDiff.RawRef(kind, owner, name, desc), o.raw(), o.fallback(), o.names(), o.resolved());
    }

    GameDiff.Ref cls(String raw) {
        return new GameDiff.Ref('c', o.names().cls(raw), null, null);
    }

    String desc(String raw) {
        return o.names().desc(raw);
    }

    GameDiff.Body run(MethodModel mm) {
        CodeModel code = mm.code().orElse(null);
        if (code == null) {
            return null;
        }
        int slot = 0;
        if ((mm.flags().flagsMask() & GameDiff.ACC_STATIC) == 0) {
            locals.put(slot++, new GameDiff.Node('p', null, 0, "L" + o.names().cls(self) + ";"));
        }
        for (ClassDesc p : mm.methodTypeSymbol().parameterList()) {
            locals.put(slot, new GameDiff.Node('p', null, slot, desc(p.descriptorString())));
            slot += p.descriptorString().equals("J") || p.descriptorString().equals("D") ? 2 : 1;
        }
        Set<Label> handlers = new HashSet<>();
        Set<Label> targets = new HashSet<>(); // where control can arrive other than by falling in: a block starts there
        for (ExceptionCatch c : code.exceptionHandlers()) {
            handlers.add(c.handler());
            targets.addAll(List.of(c.handler(), c.tryStart(), c.tryEnd()));
        }
        // Loop heads (targets of a jump back) and every local the method stores to: at a loop head or a handler
        // those locals may hold any value they get later, so they are merged values there.
        Set<Label> seen = new HashSet<>(), loops = new HashSet<>();
        Set<Integer> stored = new HashSet<>();
        for (CodeElement el : code) {
            switch (el) {
                case LabelTarget lt -> seen.add(lt.label());
                case BranchInstruction bi -> {
                    targets.add(bi.target());
                    if (seen.contains(bi.target())) {
                        loops.add(bi.target());
                    }
                }
                case LookupSwitchInstruction ls -> {
                    targets.add(ls.defaultTarget());
                    ls.cases().forEach(c -> targets.add(c.target()));
                    ls.cases().stream().map(SwitchCase::target).filter(seen::contains).forEach(loops::add);
                }
                case TableSwitchInstruction ts -> {
                    targets.add(ts.defaultTarget());
                    ts.cases().forEach(c -> targets.add(c.target()));
                    ts.cases().stream().map(SwitchCase::target).filter(seen::contains).forEach(loops::add);
                }
                case StoreInstruction si -> stored.add(si.slot());
                case IncrementInstruction ii -> stored.add(ii.slot());
                default -> {
                }
            }
        }
        boolean arrive = false;
        try {
            for (CodeElement el : code) {
                if (el instanceof LabelTarget lt) {
                    arrive |= targets.contains(lt.label());
                    State s = saved.remove(lt.label());
                    if (handlers.contains(lt.label())) {
                        stack = new ArrayList<>(List.of(phi("Ljava/lang/Throwable;")));
                    } else if (!live) {
                        stack = s == null ? new ArrayList<>() : new ArrayList<>(s.stack());
                        if (s != null) {
                            locals.clear();
                            locals.putAll(s.locals());
                        }
                    } else if (s != null) {
                        stack = merge(stack, s.stack());
                        Map<Integer, GameDiff.Node> joined = merge(locals, s.locals());
                        locals.clear();
                        locals.putAll(joined);
                    }
                    if (handlers.contains(lt.label()) || loops.contains(lt.label())) {
                        for (int s2 : stored) {
                            GameDiff.Node had = locals.get(s2);
                            locals.put(s2, phi(had == null ? "Ljava/lang/Object;" : had.type));
                        }
                    }
                    live = true;
                } else if (el instanceof Instruction ins) {
                    index++;
                    if (arrive) {
                        starts.add(index);
                        arrive = false;
                    }
                    if (ins instanceof BranchInstruction || ins instanceof LookupSwitchInstruction || ins instanceof TableSwitchInstruction
                        || ins instanceof ReturnInstruction || ins instanceof ThrowInstruction) {
                        jumps.add(index);
                    }
                    if (live) {
                        step(ins);
                    }
                }
            }
        } catch (IllegalStateException | IllegalArgumentException unreadable) {
            return body.statements.isEmpty() ? null : body;
        }
        body.whole = true;
        return body;
    }

    void step(Instruction ins) {
        switch (ins) {
            case LoadInstruction li -> {
                GameDiff.Node v = locals.get(li.slot());
                push(v != null ? v : new GameDiff.Node('l', null, li.slot(), kind(li.typeKind())));
            }
            case StoreInstruction si -> {
                GameDiff.Node v = pop();
                locals.put(si.slot(), v);
                statement("store", null, v).slot = si.slot();
            }
            case IncrementInstruction ii -> locals.put(ii.slot(), new GameDiff.Node('o', null, "iinc", "I"));
            case ConstantInstruction ci -> {
                ConstantDesc v = ci.constantValue();
                if (ci.opcode() == Opcode.ACONST_NULL) {
                    push(new GameDiff.Node('n', null, null, "null"));
                } else if (v instanceof Integer || v instanceof Long || v instanceof Float || v instanceof Double) {
                    push(new GameDiff.Node('k', null, v, kind(ci.typeKind())));
                } else if (v instanceof String s) {
                    push(new GameDiff.Node('k', null, s, "Ljava/lang/String;"));
                } else if (v instanceof ClassDesc cd) {
                    push(new GameDiff.Node('o', cls(GameDiff.internal(cd)), "class", "Ljava/lang/Class;"));
                } else {
                    push(new GameDiff.Node('o', null, "ldc", kind(ci.typeKind())));
                }
            }
            case StackInstruction si -> stack(si.opcode());
            case OperatorInstruction oi -> {
                String name = oi.opcode().name();
                int n = name.endsWith("NEG") || name.equals("ARRAYLENGTH") ? 1 : 2;
                boolean compare = name.equals("ARRAYLENGTH") || name.endsWith("CMP") || name.endsWith("CMPL") || name.endsWith("CMPG");
                push(new GameDiff.Node('o', null, name, compare ? "I" : kind(oi.typeKind()), pop(n)));
            }
            case ConvertInstruction ci -> push(new GameDiff.Node('x', null, null, kind(ci.toType()), pop()));
            case FieldInstruction fi -> {
                GameDiff.Ref r = member('f', fi.owner().asInternalName(), fi.name().stringValue(), fi.type().stringValue());
                switch (fi.opcode()) {
                    case GETSTATIC -> push(new GameDiff.Node('g', r, null, r.desc()));
                    case GETFIELD -> push(new GameDiff.Node('f', r, null, r.desc(), pop()));
                    case PUTFIELD -> statement("put", r, pop(2));
                    default -> statement("put", r, pop());
                }
            }
            case InvokeInstruction ii -> invoke(ii);
            case InvokeDynamicInstruction di -> {
                GameDiff.Node[] args = pop(di.typeSymbol().parameterCount());
                String type = desc(di.typeSymbol().returnType().descriptorString());
                if (di.bootstrapMethod().owner().descriptorString().equals("Ljava/lang/invoke/LambdaMetafactory;")
                    && di.bootstrapArgs().size() > 1 && di.bootstrapArgs().get(1) instanceof DirectMethodHandleDesc h) {
                    String owner = GameDiff.internal(h.owner());
                    if (owner.equals(self) && h.methodName().startsWith("lambda$")) {
                        body.lambdas.add(h.methodName() + h.lookupDescriptor());
                        GameDiff.Node lambda = new GameDiff.Node('y', null, di.name().stringValue(), type, args);
                        lambda.body = h.methodName() + h.lookupDescriptor();
                        push(lambda);
                    } else {
                        push(new GameDiff.Node('y', member('m', owner, h.methodName(), h.lookupDescriptor()), di.name().stringValue(), type, args));
                    }
                } else {
                    push(new GameDiff.Node('o', null, "indy:" + di.bootstrapMethod().methodName(), type, args));
                }
            }
            case NewObjectInstruction ni -> push(new GameDiff.Node('u', cls(ni.className().asInternalName()), null, "L" + o.names().cls(ni.className().asInternalName()) + ";"));
            case NewPrimitiveArrayInstruction na -> push(new GameDiff.Node('o', null, "newarray", "[" + kind(na.typeKind()), pop()));
            case NewReferenceArrayInstruction na -> push(new GameDiff.Node('o', cls(na.componentType().asInternalName()), "anewarray", "[Ljava/lang/Object;", pop()));
            case NewMultiArrayInstruction na -> push(new GameDiff.Node('o', cls(na.arrayType().asInternalName()), "multianewarray", desc(na.arrayType().asInternalName()), pop(na.dimensions())));
            case ArrayLoadInstruction al -> push(new GameDiff.Node('o', null, al.opcode().name(), kind(al.typeKind()), pop(2)));
            case ArrayStoreInstruction as -> statement("astore", null, pop(3));
            case TypeCheckInstruction tc -> {
                GameDiff.Ref c = cls(tc.type().asInternalName());
                String t = c.owner().startsWith("[") ? c.owner() : "L" + c.owner() + ";";
                push(tc.opcode() == Opcode.CHECKCAST ? new GameDiff.Node('t', c, null, t, pop()) : new GameDiff.Node('o', c, "instanceof", "I", pop()));
            }
            case BranchInstruction bi -> {
                String name = bi.opcode().name();
                if (name.startsWith("GOTO")) {
                    save(bi.target());
                    live = false;
                    return;
                }
                int n = name.startsWith("IF_") ? 2 : 1;
                statement("if:" + name, null, pop(n));
                save(bi.target());
            }
            case LookupSwitchInstruction ls -> {
                statement("switch", null, pop());
                save(ls.defaultTarget());
                ls.cases().forEach(c -> save(c.target()));
                live = false;
            }
            case TableSwitchInstruction ts -> {
                statement("switch", null, pop());
                save(ts.defaultTarget());
                ts.cases().forEach(c -> save(c.target()));
                live = false;
            }
            case ReturnInstruction ri -> {
                if (ri.typeKind() == TypeKind.VOID) {
                    statement("return", null);
                } else {
                    statement("return", null, pop());
                }
                live = false;
            }
            case ThrowInstruction ti -> {
                statement("throw", null, pop());
                live = false;
            }
            case MonitorInstruction mi -> statement("monitor", null, pop());
            case NopInstruction nop -> {
            }
            default -> throw new IllegalStateException("not read: " + ins.opcode());
        }
    }

    void invoke(InvokeInstruction ii) {
        MethodTypeDesc t = ii.typeSymbol();
        String name = ii.name().stringValue();
        GameDiff.Ref r = member('m', ii.owner().asInternalName(), name, ii.type().stringValue());
        GameDiff.Node[] args = pop(t.parameterCount());
        String ret = desc(t.returnType().descriptorString());
        if (ii.opcode() == Opcode.INVOKESTATIC) {
            result(new GameDiff.Node('s', r, null, ret, args));
            return;
        }
        GameDiff.Node receiver = pop();
        if (name.equals("<init>") && receiver.op == 'u') {
            GameDiff.Node made = new GameDiff.Node('w', r, null, receiver.type, args);
            made.at = index;
            stack.replaceAll(n -> n == receiver ? made : n);
            locals.replaceAll((k, n) -> n == receiver ? made : n);
            return;
        }
        GameDiff.Node[] all = new GameDiff.Node[args.length + 1];
        all[0] = receiver;
        System.arraycopy(args, 0, all, 1, args.length);
        if (name.equals("<init>")) {
            statement("init", r, all);
            return;
        }
        result(new GameDiff.Node('v', r, ii.opcode() == Opcode.INVOKESPECIAL ? "special" : null, ret, all));
    }

    void result(GameDiff.Node call) {
        call.at = index;
        if (call.type.equals("V")) {
            statement("expr", null, call);
        } else {
            push(call);
        }
    }

    void stack(Opcode op) {
        switch (op) {
            case POP -> discard(pop());
            case POP2 -> {
                GameDiff.Node v = pop();
                discard(v);
                if (!wide(v)) {
                    discard(pop());
                }
            }
            case DUP -> {
                GameDiff.Node v = pop();
                push(v);
                push(v);
            }
            case DUP_X1 -> {
                GameDiff.Node v1 = pop(), v2 = pop();
                push(v1);
                push(v2);
                push(v1);
            }
            case DUP_X2 -> {
                GameDiff.Node v1 = pop(), v2 = pop();
                if (wide(v2)) {
                    push(v1);
                    push(v2);
                    push(v1);
                } else {
                    GameDiff.Node v3 = pop();
                    push(v1);
                    push(v3);
                    push(v2);
                    push(v1);
                }
            }
            case DUP2 -> {
                GameDiff.Node v1 = pop();
                if (wide(v1)) {
                    push(v1);
                    push(v1);
                } else {
                    GameDiff.Node v2 = pop();
                    push(v2);
                    push(v1);
                    push(v2);
                    push(v1);
                }
            }
            case DUP2_X1 -> {
                GameDiff.Node v1 = pop();
                if (wide(v1)) {
                    GameDiff.Node v2 = pop();
                    push(v1);
                    push(v2);
                    push(v1);
                } else {
                    GameDiff.Node v2 = pop(), v3 = pop();
                    push(v2);
                    push(v1);
                    push(v3);
                    push(v2);
                    push(v1);
                }
            }
            case DUP2_X2 -> throw new IllegalStateException("dup2_x2");
            case SWAP -> {
                GameDiff.Node v1 = pop(), v2 = pop();
                push(v1);
                push(v2);
            }
            default -> throw new IllegalStateException("stack op " + op);
        }
    }

    /** A value computed and dropped: a call made for what it does is a statement. */
    void discard(GameDiff.Node v) {
        if (v.op == 's' || v.op == 'v' || v.op == 'w') {
            statement("expr", null, v);
        }
    }
}
