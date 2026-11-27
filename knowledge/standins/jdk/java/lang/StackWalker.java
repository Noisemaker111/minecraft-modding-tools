package mmt.standin.java.lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Java 9's java.lang.StackWalker, for a release on Java 8, over the stack trace a Throwable records, from the JDK's
 * documented contract: walk(function) "applies the given function to the stream of StackFrames for the current thread,
 * traversing from the top frame of the stack, which is the method calling this walk method"; forEach(action) "performs
 * the given action on each element of StackFrame stream of the current thread, traversing from the top frame of the
 * stack, which is the method calling this forEach method". By default "reflection frames are hidden": Method.invoke and
 * Constructor.newInstance and the frames of their implementation (sun.reflect, jdk.internal.reflect), which
 * SHOW_REFLECT_FRAMES keeps. Hidden frames (lambda forms) are never in a Throwable's trace, so SHOW_HIDDEN_FRAMES shows
 * the same frames. A frame answers getClassName(), getMethodName(), getFileName(), getLineNumber(), isNativeMethod()
 * and toStackTraceElement() from its StackTraceElement. getDeclaringClass() and getCallerClass() need the frames'
 * classes, and getByteCodeIndex() the index of each frame's instruction, which a Java 8 trace does not hold: they are
 * not written here, and a mod calling them keeps a named break.
 */
public final class StackWalker {

    public enum Option {
        RETAIN_CLASS_REFERENCE, SHOW_REFLECT_FRAMES, SHOW_HIDDEN_FRAMES
    }

    public interface StackFrame {
        java.lang.String getClassName();

        java.lang.String getMethodName();

        java.lang.String getFileName();

        int getLineNumber();

        boolean isNativeMethod();

        java.lang.StackTraceElement toStackTraceElement();
    }

    private static final StackWalker DEFAULT = new StackWalker(EnumSet.noneOf(Option.class));

    private final Set<Option> options;

    private StackWalker(Set<Option> options) {
        this.options = options;
    }

    public static StackWalker getInstance() {
        return DEFAULT;
    }

    public static StackWalker getInstance(Option option) {
        return new StackWalker(EnumSet.of(Objects.requireNonNull(option)));
    }

    public static StackWalker getInstance(Set<Option> options) {
        return options.isEmpty() ? DEFAULT : new StackWalker(EnumSet.copyOf(options));
    }

    public static StackWalker getInstance(Set<Option> options, int estimateDepth) {
        if (estimateDepth <= 0) {
            throw new IllegalArgumentException("estimateDepth must be > 0");
        }
        return getInstance(options);
    }

    public <T> T walk(Function<? super Stream<StackFrame>, ? extends T> function) {
        Objects.requireNonNull(function);
        return function.apply(frames().stream());
    }

    public void forEach(Consumer<? super StackFrame> action) {
        Objects.requireNonNull(action);
        frames().forEach(action);
    }

    /** The current thread's frames from the method that called walk or forEach, this class's own left out. */
    private List<StackFrame> frames() {
        java.lang.StackTraceElement[] trace = new Throwable().getStackTrace();
        java.lang.String self = StackWalker.class.getName();
        int top = 0;
        while (top < trace.length && trace[top].getClassName().equals(self)) {
            top++;
        }
        boolean reflect = options.contains(Option.SHOW_REFLECT_FRAMES) || options.contains(Option.SHOW_HIDDEN_FRAMES);
        List<StackFrame> out = new ArrayList<>(trace.length - top);
        for (int i = top; i < trace.length; i++) {
            if (reflect || !reflection(trace[i])) {
                out.add(new Frame(trace[i]));
            }
        }
        return Collections.unmodifiableList(out);
    }

    static boolean reflection(java.lang.StackTraceElement e) {
        java.lang.String c = e.getClassName();
        return c.startsWith("sun.reflect.") || c.startsWith("jdk.internal.reflect.")
            || c.equals("java.lang.reflect.Method") && e.getMethodName().equals("invoke")
            || c.equals("java.lang.reflect.Constructor") && e.getMethodName().equals("newInstance");
    }

    static final class Frame implements StackFrame {

        final java.lang.StackTraceElement e;

        Frame(java.lang.StackTraceElement e) {
            this.e = e;
        }

        public java.lang.String getClassName() {
            return e.getClassName();
        }

        public java.lang.String getMethodName() {
            return e.getMethodName();
        }

        public java.lang.String getFileName() {
            return e.getFileName();
        }

        public int getLineNumber() {
            return e.getLineNumber();
        }

        public boolean isNativeMethod() {
            return e.isNativeMethod();
        }

        public java.lang.StackTraceElement toStackTraceElement() {
            return e;
        }

        @Override
        public java.lang.String toString() {
            return e.toString();
        }
    }
}
