import java.util.regex.*;
import org.objectweb.asm.*;

/** A call to a method that gained a parameter: the value the game's own callers pass for it. */
final class DefaultedArguments {

    final Sites sites;

    DefaultedArguments(Sites sites) {
        this.sites = sites;
    }

    /** A call to a method that gained one parameter, with the one value the game's own code passes there inserted. */
    boolean defaulted(String key, String owner, String desc, int opcode) {
        JarPort.Default d = sites.port.defaults.get(key);
        if (d == null || opcode == Opcodes.INVOKESTATIC || opcode == Opcodes.INVOKESPECIAL) {
            return false;
        }
        Type[] now = Type.getArgumentTypes(d.to().desc());
        Type[] before = Type.getArgumentTypes(sites.port.desc(desc));
        if (now.length != before.length + 1 || !sites.isA(sites.port.cls(owner), d.to().owner()) || !sites.port.links.linksNew(sites.mod, sites.callers, d.to().owner(), 'm', d.to().name(), d.to().desc())) {
            return false;
        }
        Runnable value = value(d.value(), now[d.index()]);
        if (value == null) {
            return false;
        }
        Type[] args = Type.getArgumentTypes(desc);
        int[] slots = sites.spill(args, d.index());
        value.run();
        for (int i = d.index(); i < args.length; i++) {
            sites.emitVarInsn(args[i].getOpcode(Opcodes.ILOAD), slots[i]);
        }
        sites.invoke(false, d.to());
        sites.mod.change("call given the game's default argument");
        return true;
    }

    /** Code that pushes the value the game's code passes ("a.b.C::m" as an instance of wanted, "a.b.C.FIELD", "a.b.C.m()"), or null. */
    Runnable value(String text, Type wanted) {
        Matcher ref = MixinMembers.REFERENCE.matcher(text);
        if (ref.matches()) {
            Object[] indy = sites.port.links.lambda(sites.mod, sites.callers, ref.group(1).replace('.', '/'), ref.group(2), wanted);
            return indy == null ? null : () -> sites.emitInvokeDynamicInsn((String) indy[0], (String) indy[1], (Handle) indy[2], (Object[]) indy[3]);
        }
        Matcher call = MixinMembers.STATIC_CALL.matcher(text);
        Matcher field = MixinMembers.STATIC_FIELD.matcher(text);
        boolean isCall = call.matches();
        if (!isCall && !field.matches()) {
            return null;
        }
        String owner = (isCall ? call : field).group(1).replace('.', '/');
        String name = (isCall ? call : field).group(2);
        GameDiff.Cls holder = sites.port.b.classes.get(owner);
        if (holder == null) {
            return null;
        }
        if (isCall) {
            GameDiff.Meth m = holder.methods.values().stream()
                .filter(x -> x.name.equals(name) && x.desc.startsWith("()") && (x.flags & Opcodes.ACC_STATIC) != 0).findFirst().orElse(null);
            if (m == null || !sites.port.links.usable(Type.getReturnType(m.desc), wanted) || !sites.port.links.memberOpen(sites.mod, sites.callers, owner, m.name + m.desc, m.flags)) {
                return null;
            }
            boolean itf = (holder.flags & Opcodes.ACC_INTERFACE) != 0;
            return () -> sites.emitMethodInsn(Opcodes.INVOKESTATIC, sites.port.inverse.getOrDefault(owner, owner), m.name, sites.port.oldDesc(m.desc), itf);
        }
        GameDiff.Fld f = holder.fields.get(name);
        if (f == null || (f.flags & Opcodes.ACC_STATIC) == 0 || !sites.port.links.usable(Type.getType(f.desc), wanted) || !sites.port.links.memberOpen(sites.mod, sites.callers, owner, name, f.flags)) {
            return null;
        }
        return () -> sites.emitFieldInsn(Opcodes.GETSTATIC, sites.port.inverse.getOrDefault(owner, owner), name, sites.port.oldDesc(f.desc));
    }
}
