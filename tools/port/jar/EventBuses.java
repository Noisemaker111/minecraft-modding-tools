import java.util.*;
import org.objectweb.asm.*;

/** Event subscribers the loader now splits by bus: which bus each listener is on, and the companion class the mod-bus ones move to. */
final class EventBuses {

    final JarPort port;

    EventBuses(JarPort port) {
        this.port = port;
    }

    static final String SUBSCRIBER = "Lnet/neoforged/fml/common/EventBusSubscriber;";
    static final String LISTENER = "Lnet/neoforged/bus/api/SubscribeEvent;";

    /** A @SubscribeEvent method and its annotation's values ({name, value} or {name, enum desc, value}). */
    record Listener(String name, String desc, int access, String event, List<Object[]> values) {}

    /**
     * Newer loaders send each listener of an @EventBusSubscriber class that names no bus to the bus its event belongs to;
     * older ones (1.21.2's NeoForge, FML 4.0.29) put the whole class on the game bus, and refuse a mod-bus event there.
     * A class whose listeners are all mod-bus events gets bus = MOD written, which every loader honours. A class with
     * both kinds keeps its game-bus listeners; its mod-bus ones are called from a companion class on the mod bus.
     */
    void buses(JarPort.Mod mod) {
        for (var e : mod.subscribers.entrySet()) {
            List<String> events = mod.listeners.getOrDefault(e.getKey(), List.of());
            long onMod = events.stream().filter(t -> modBus(mod, port.cls(t))).count();
            if (e.getValue() || onMod == 0) {
                continue;
            }
            if (onMod == events.size()) {
                mod.toModBus.add(e.getKey());
                continue;
            }
            List<Listener> moved = mod.listenerMethods.getOrDefault(e.getKey(), List.of()).stream().filter(l -> modBus(mod, port.cls(l.event()))).toList();
            if (moved.stream().allMatch(l -> (l.access() & Opcodes.ACC_STATIC) != 0)) {
                mod.split.put(e.getKey(), moved);
            } else {
                mod.unlinked.add("listeners for both event buses in one @EventBusSubscriber class: " + Exposure.dotted(e.getKey()));
            }
        }
    }

    /** The companion of a subscriber with listeners on both buses: on the mod bus, each listener calls the original. */
    byte[] companion(JarPort.Mod mod, String owner, List<Listener> listeners) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, owner + "$ModBusListeners", null, "java/lang/Object", null);
        AnnotationVisitor av = w.visitAnnotation(SUBSCRIBER, true);
        for (Object[] v : mod.subscriberValues.getOrDefault(owner, List.of())) {
            if (v[1] instanceof List<?> items) {
                AnnotationVisitor array = av.visitArray((String) v[0]);
                for (Object item : items) {
                    String[] e = (String[]) item;
                    array.visitEnum(null, e[0], e[1]);
                }
                array.visitEnd();
            } else {
                av.visit((String) v[0], v[1]);
            }
        }
        av.visitEnum("bus", "Lnet/neoforged/fml/common/EventBusSubscriber$Bus;", "MOD");
        av.visitEnd();
        for (Listener l : listeners) {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, l.name(), l.desc(), null, null);
            AnnotationVisitor sub = m.visitAnnotation(LISTENER, true);
            for (Object[] v : l.values()) {
                if (v.length == 3) {
                    sub.visitEnum((String) v[0], (String) v[1], (String) v[2]);
                } else {
                    sub.visit((String) v[0], v[1]);
                }
            }
            sub.visitEnd();
            m.visitCode();
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, owner, l.name(), l.desc(), false);
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(1, 1);
            m.visitEnd();
        }
        w.visitEnd();
        return w.toByteArray();
    }

    /** Whether an event (new names) is dispatched on a mod's own bus: the loader's lifecycle events and every IModBusEvent. */
    boolean modBus(JarPort.Mod mod, String event) {
        return event.startsWith("net/neoforged/fml/event/") || port.links.isA(mod, event, "net/neoforged/fml/event/IModBusEvent");
    }
}
