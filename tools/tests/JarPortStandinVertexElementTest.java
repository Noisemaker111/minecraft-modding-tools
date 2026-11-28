import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;

/**
 * The 1.21 -> 1.20.6 stand-in for VertexFormatElement's id registry (knowledge/standins/1.21-1.20.6/), compiled from its
 * file and run against 1.20.6's VertexFormatElement and DefaultVertexFormat as 1.20.6 writes them (an element is index,
 * type, usage and count, equal by those). A mod that adds vertex attributes, as Iris 1.21.1 does, finds the next free id
 * by asking byId until it answers null, then registers there; each registration must take its own id and mask bit, a
 * taken or out-of-range id is refused as 1.21 refuses it, and an element that equals a built-in by value keeps the id it
 * was registered under.
 */
final class JarPortStandinVertexElementTest {

    static final String ELEMENT_1_20_6 = """
        package com.mojang.blaze3d.vertex;
        public class VertexFormatElement {
            private final Type type;
            private final Usage usage;
            private final int index;
            private final int count;
            public VertexFormatElement(int index, Type type, Usage usage, int count) {
                if (index != 0 && usage != Usage.UV) {
                    throw new IllegalStateException("Multiple vertex elements of the same type other than UVs are not supported");
                }
                this.index = index;
                this.type = type;
                this.usage = usage;
                this.count = count;
            }
            public final int getByteSize() {
                return type.size * count;
            }
            @Override
            public boolean equals(Object o) {
                return this == o || o != null && getClass() == o.getClass() && count == ((VertexFormatElement) o).count
                    && index == ((VertexFormatElement) o).index && type == ((VertexFormatElement) o).type && usage == ((VertexFormatElement) o).usage;
            }
            @Override
            public int hashCode() {
                return java.util.Objects.hash(type, usage, index, count);
            }
            public enum Type {
                FLOAT(4), UBYTE(1), BYTE(1), USHORT(2), SHORT(2), UINT(4), INT(4);
                final int size;
                Type(int size) {
                    this.size = size;
                }
            }
            public enum Usage {
                POSITION, NORMAL, COLOR, UV, PADDING, GENERIC
            }
        }
        """;

    static final String DEFAULTS_1_20_6 = """
        package com.mojang.blaze3d.vertex;
        import com.mojang.blaze3d.vertex.VertexFormatElement.Type;
        import com.mojang.blaze3d.vertex.VertexFormatElement.Usage;
        public class DefaultVertexFormat {
            public static final VertexFormatElement ELEMENT_POSITION = new VertexFormatElement(0, Type.FLOAT, Usage.POSITION, 3);
            public static final VertexFormatElement ELEMENT_COLOR = new VertexFormatElement(0, Type.UBYTE, Usage.COLOR, 4);
            public static final VertexFormatElement ELEMENT_UV0 = new VertexFormatElement(0, Type.FLOAT, Usage.UV, 2);
            public static final VertexFormatElement ELEMENT_UV1 = new VertexFormatElement(1, Type.SHORT, Usage.UV, 2);
            public static final VertexFormatElement ELEMENT_UV2 = new VertexFormatElement(2, Type.SHORT, Usage.UV, 2);
            public static final VertexFormatElement ELEMENT_NORMAL = new VertexFormatElement(0, Type.BYTE, Usage.NORMAL, 3);
        }
        """;

    static final Path STANDIN = Path.of("knowledge/standins/1.21-1.20.6/com/mojang/blaze3d/vertex/VertexFormatElement.java");

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        JarPortBackTest.compile(scratch, Map.of(
            "com/mojang/blaze3d/vertex/VertexFormatElement.java", ELEMENT_1_20_6,
            "com/mojang/blaze3d/vertex/DefaultVertexFormat.java", DEFAULTS_1_20_6,
            "mmt/standin/com/mojang/blaze3d/vertex/VertexFormatElement.java", Files.readString(STANDIN)), null);
        try (URLClassLoader loader = new URLClassLoader(new URL[] { scratch.resolve("classes").toUri().toURL() }, null)) {
            Class<?> element = loader.loadClass("com.mojang.blaze3d.vertex.VertexFormatElement");
            Class<?> type = loader.loadClass("com.mojang.blaze3d.vertex.VertexFormatElement$Type");
            Class<?> usage = loader.loadClass("com.mojang.blaze3d.vertex.VertexFormatElement$Usage");
            Class<?> standin = loader.loadClass("mmt.standin.com.mojang.blaze3d.vertex.VertexFormatElement");
            Method byId = standin.getMethod("byId", int.class), id = standin.getMethod("id", element), mask = standin.getMethod("mask", element);
            Method register = standin.getMethod("register", int.class, int.class, type, usage, int.class);
            Object shortType = enumValue(type, "SHORT"), generic = enumValue(usage, "GENERIC");

            // As Iris allocates its attributes: the first id byId answers null for, registered there, twice over.
            List<Object> made = new ArrayList<>();
            for (int k = 0; k < 2; k++) {
                int free = 0;
                while (byId.invoke(null, free) != null) {
                    free++;
                }
                made.add(register.invoke(null, free, 0, shortType, generic, 2));
            }
            int first = (Integer) id.invoke(null, made.get(0)), second = (Integer) id.invoke(null, made.get(1));
            JarPortTest.expect(first == 6 && second == 7, "two allocations after the six built-ins take ids 6 and 7: " + first + ", " + second);
            JarPortTest.expect((Integer) mask.invoke(null, made.get(0)) == 1 << 6 && (Integer) mask.invoke(null, made.get(1)) == 1 << 7,
                "each takes its own mask bit: " + mask.invoke(null, made.get(0)) + ", " + mask.invoke(null, made.get(1)));
            JarPortTest.expect(byId.invoke(null, 6) == made.get(0) && byId.invoke(null, 7) == made.get(1), "byId answers each registered element under its id");

            // An element equal by value to a built-in (1.20.6 compares index, type, usage, count) keeps its own id.
            Object position = register.invoke(null, 8, 0, enumValue(type, "FLOAT"), enumValue(usage, "POSITION"), 3);
            JarPortTest.expect((Integer) id.invoke(null, position) == 8, "an element equal to POSITION registered at 8 has id 8: " + id.invoke(null, position));
            JarPortTest.expect((Integer) id.invoke(null, standin.getMethod("POSITION").invoke(null)) == 0, "POSITION keeps id 0");

            JarPortTest.expect(refused(register, 6, shortType, generic), "a taken id is refused, as 1.21's register refuses a duplicate");
            JarPortTest.expect(refused(register, 32, shortType, generic) && refused(register, -1, shortType, generic), "an id outside [0; 32) is refused");

            Method fromMask = standin.getMethod("elementsFromMask", int.class);
            List<?> both = ((java.util.stream.Stream<?>) fromMask.invoke(null, (1 << 6) | (1 << 7))).toList();
            JarPortTest.expect(both.equals(made), "elementsFromMask streams the registered elements whose bits are set: " + both);
        }
        JarPortTest.finish("JarPortStandinVertexElementTest");
    }

    static Object enumValue(Class<?> type, String name) {
        return Arrays.stream(type.getEnumConstants()).filter(c -> ((Enum<?>) c).name().equals(name)).findFirst().orElseThrow();
    }

    static boolean refused(Method register, int at, Object type, Object usage) throws IllegalAccessException {
        try {
            register.invoke(null, at, 0, type, usage, 2);
            return false;
        } catch (InvocationTargetException e) {
            return e.getCause() instanceof IllegalArgumentException;
        }
    }
}
