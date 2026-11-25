import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A Java agent that lets a loader's own launch run the game's start-up code and ends the JVM where the game would
 * make a world or a window: start-check's one cold JVM.
 *
 * The launch is the loader's real one, unchanged (the installed server's, or the client's as a launcher writes it),
 * so everything before that point is decided by the loader, Mixin and the game themselves, in their own words: the
 * loader's dependency resolver, mixins applied to each class the start loads, Bootstrap, the mods' own start code,
 * and on a server the data packs a new world loads (recipes, tags, registries). Where it ends:
 *
 * - a server, when the thread named "Server thread" defines its first class. The game starts that thread once
 *   Main has loaded the data packs, and the thread's first work is to open the port and the world. That name is the
 *   game's own on every release.
 * - a client, when the game calls GLFW.glfwInit(), the first step of making its window: Bootstrap and (on Fabric)
 *   the mods' client start code have run. The call is replaced, in the one class as it loads, by a call back here.
 *
 * Mixin applies a mixin when its target class loads, and a start only loads some of the game. So as the game's entry
 * class loads (Mixin has read every config by then), MmtMixinCheck loads every class the configs target: a mixin the
 * game would only reach in a world is applied by Mixin here. One that Mixin refuses with an error the game stops on
 * ends the JVM there, a few seconds in; with none, every mixin is in place and the start goes on as the game's own.
 *
 * Usage: -javaagent:mmt-nogame.jar=server (or =client), with -Dmmt.mixincheck.out naming the file of events: Mixin's
 * (MmtMixinCheck's), and "stop" with why the JVM ended. A start that ends without one ended by itself: it failed.
 * Java 8 syntax, no dependencies: it runs on whichever Java the release does.
 */
public final class MmtNoGame implements ClassFileTransformer, Runnable {

    static final long START = System.nanoTime();
    static final AtomicBoolean STOPPED = new AtomicBoolean();
    static final String SERVER_THREAD = "Server thread";
    static final String WINDOW_CLASS = "org/lwjgl/glfw/GLFW";
    /** The game's entry classes: the dedicated server's main (MinecraftServer's own before 1.16) and the client's. */
    static final String SERVER_MAIN = "net/minecraft/server/Main";
    static final String OLD_SERVER_MAIN = "net/minecraft/server/MinecraftServer";
    static final String CLIENT_MAIN = "net/minecraft/client/main/Main";
    static final AtomicBoolean MIXINS_READ = new AtomicBoolean();
    static final AtomicBoolean WINDOW_REPLACED = new AtomicBoolean();
    static Instrumentation instrumentation;
    static volatile Thread stopping;
    /** The system property the replaced glfwInit reads this agent from: java.base is all a class in any module can name. */
    static final String KEY = "mmt.nogame.stop";
    static String side = "server";
    /** Asked for with "SIDE,mixins": the check ends once the mixins are read, for a start that is only asked whether they apply. */
    static boolean mixinsOnly;
    static final String MIXINS_STOP = "the mixins were read";

    public static void premain(String arguments, Instrumentation instrumentation) {
        List<String> asked = Arrays.asList(String.valueOf(arguments).split(","));
        side = asked.contains("client") ? "client" : "server";
        mixinsOnly = asked.contains("mixins");
        MmtNoGame agent = new MmtNoGame();
        if (side.equals("client")) {
            System.getProperties().put(KEY, agent);
        }
        MmtNoGame.instrumentation = instrumentation;
        instrumentation.addTransformer(agent, true);
        if (side.equals("server")) {
            // The server thread's first line is a second sign of it, for a start that defines no class there at once.
            System.setOut(watched(System.out));
        }
    }

    /** The console as it was, in the encoding it had (the JVM's own name for it, on the Javas that say), with the watch in front. */
    static PrintStream watched(PrintStream console) {
        String encoding = System.getProperty("stdout.encoding", System.getProperty("sun.stdout.encoding"));
        try {
            return encoding == null ? new PrintStream(new Watched(console), true) : new PrintStream(new Watched(console), true, encoding);
        } catch (java.io.UnsupportedEncodingException unknown) {
            return new PrintStream(new Watched(console), true);
        }
    }

    /** The game's console: what the server thread writes first ends the check, everything else passes through. */
    static final class Watched extends FilterOutputStream {

        Watched(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] bytes, int from, int length) throws IOException {
            if (SERVER_THREAD.equals(Thread.currentThread().getName())) {
                stop("the game started its server thread");
            }
            out.write(bytes, from, length);
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain domain, byte[] bytes) {
        if (side.equals("server") && SERVER_THREAD.equals(Thread.currentThread().getName())) {
            stop("the game started its server thread");
        }
        boolean entry = side.equals("server") ? SERVER_MAIN.equals(name) || OLD_SERVER_MAIN.equals(name) : CLIENT_MAIN.equals(name);
        if (entry && loader != null && MIXINS_READ.compareAndSet(false, true)) {
            readMixins(loader, name.replace('/', '.'));
            if (mixinsOnly) stop(MIXINS_STOP);
            windowStillReplaced();
        }
        if (side.equals("client") && WINDOW_CLASS.equals(name)) {
            try {
                byte[] replaced = callInsteadOfWindow(bytes);
                WINDOW_REPLACED.set(true);
                return replaced;
            } catch (Throwable failure) {
                // A window must never open here: without the replaced call the check ends where it is.
                MmtMixinCheck.emit("note", "message", "Could not replace GLFW.glfwInit, so the check ends as GLFW loads: " + failure);
                stop("the game loaded its window library");
            }
        }
        return null;
    }

    /** What the replaced glfwInit calls. */
    @Override
    public void run() {
        stop("the game asked for its window");
    }

    /**
     * Every mixin target loaded through the game's loader, as its entry class is being defined (defining is that class:
     * it cannot be asked for again from inside its own definition, and Mixin has just applied its mixins). Ends the JVM
     * when Mixin refused one with an error the game stops on.
     */
    static void readMixins(ClassLoader game, String defining) {
        int refused;
        // Mixin's error handler is a class of Mixin's own loader: a system property is all this agent shares with it.
        System.setProperty("mmt.mixincheck.asTheGame", "true");
        try {
            refused = MmtMixinCheck.check(game, side.toUpperCase(Locale.ROOT), START, defining);
        } catch (ClassNotFoundException noMixin) {
            MmtMixinCheck.emit("note", "message", "This launch has no Mixin (" + noMixin.getMessage() + "), so no mixins were read.");
            return;
        } catch (Throwable failure) {
            Throwable cause = MmtMixinCheck.unwrap(failure);
            MmtMixinCheck.emit("error", "phase", "check", "message", MmtMixinCheck.messages(cause), "exception", cause.getClass().getName());
            Runtime.getRuntime().halt(4);
            return;
        }
        if (refused > 0 && STOPPED.compareAndSet(false, true)) {
            MmtMixinCheck.emit("stop", "reason", "Mixin refused a mixin the game stops on", "side", side, "seconds", MmtMixinCheck.seconds(START));
            Runtime.getRuntime().halt(0);
        }
    }

    /**
     * The JVM does not hand this agent the classes that load while it is at work in transform (reading the mixins is):
     * had GLFW loaded then, it loaded as it is, window and all. It is handed over again here, from a thread of its own
     * (this one is still inside transform, where the JVM would skip the agent again), and with no replacement the check
     * ends rather than go on to a window.
     */
    static void windowStillReplaced() {
        if (!side.equals("client") || WINDOW_REPLACED.get()) return;
        for (final Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (!loaded.getName().equals(WINDOW_CLASS.replace('/', '.'))) continue;
            Thread again = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        instrumentation.retransformClasses(loaded);
                    } catch (Throwable failure) {
                        MmtMixinCheck.emit("note", "message", "GLFW loaded while the mixins were read and could not be replaced: " + failure);
                    }
                }
            }, "mmt start-check: GLFW");
            again.setDaemon(true);
            again.start();
            try {
                again.join();
            } catch (InterruptedException interrupted) {
                // judged below by whether the replacement happened
            }
            if (!WINDOW_REPLACED.get()) stop("the game loaded its window library");
        }
    }

    static void stop(String reason) {
        if (!STOPPED.compareAndSet(false, true)) {
            // Writing the last event can itself load a class or print on the thread that is stopping: it goes on to end the JVM.
            if (stopping == Thread.currentThread()) return;
            // Any other thread waits for the JVM to end.
            while (true) {
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException ignored) {
                    // still stopping
                }
            }
        }
        stopping = Thread.currentThread();
        System.getProperties().remove(KEY);
        if (!MIXINS_READ.get()) {
            MmtMixinCheck.emit("note", "message", "The game's entry class never loaded, so no mixins were read.");
        }
        MmtMixinCheck.emit("stop", "reason", reason, "side", side, "seconds", MmtMixinCheck.seconds(START));
        Runtime.getRuntime().halt(0);
    }

    /**
     * The class file with glfwInit()'s body replaced by ((Runnable) System.getProperties().get(KEY)).run(); return false.
     * Written on the bytes, with no library: the constants the call needs are added at the end of the pool, and the
     * method's Code attribute is the seven instructions alone (straight-line code needs no stack map).
     */
    static byte[] callInsteadOfWindow(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(bytes.length + 512);
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(in.readInt());
        out.writeShort(in.readUnsignedShort());
        out.writeShort(in.readUnsignedShort());
        int count = in.readUnsignedShort();
        out.writeShort(count + 20);
        String[] utf8 = new String[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            out.writeByte(tag);
            switch (tag) {
                case 1:
                    utf8[i] = in.readUTF();
                    out.writeUTF(utf8[i]);
                    break;
                case 7:
                case 8:
                case 16:
                case 19:
                case 20:
                    out.writeShort(in.readUnsignedShort());
                    break;
                case 15:
                    out.writeByte(in.readUnsignedByte());
                    out.writeShort(in.readUnsignedShort());
                    break;
                case 3:
                case 4:
                case 9:
                case 10:
                case 11:
                case 12:
                case 17:
                case 18:
                    out.writeInt(in.readInt());
                    break;
                case 5:
                case 6:
                    out.writeLong(in.readLong());
                    i++;
                    break;
                default:
                    throw new IOException("unknown constant tag " + tag);
            }
        }
        int n = count;
        utf8(out, "java/lang/System");
        classRef(out, n);
        utf8(out, "getProperties");
        utf8(out, "()Ljava/util/Properties;");
        nameAndType(out, n + 2, n + 3);
        memberRef(out, 10, n + 1, n + 4);
        utf8(out, KEY);
        out.writeByte(8);
        out.writeShort(n + 6);
        utf8(out, "java/util/Properties");
        classRef(out, n + 8);
        utf8(out, "get");
        utf8(out, "(Ljava/lang/Object;)Ljava/lang/Object;");
        nameAndType(out, n + 10, n + 11);
        memberRef(out, 10, n + 9, n + 12);
        utf8(out, "java/lang/Runnable");
        classRef(out, n + 14);
        utf8(out, "run");
        utf8(out, "()V");
        nameAndType(out, n + 16, n + 17);
        memberRef(out, 11, n + 15, n + 18);
        out.writeShort(in.readUnsignedShort());
        out.writeShort(in.readUnsignedShort());
        out.writeShort(in.readUnsignedShort());
        int interfaces = in.readUnsignedShort();
        out.writeShort(interfaces);
        for (int i = 0; i < interfaces; i++) {
            out.writeShort(in.readUnsignedShort());
        }
        int fields = in.readUnsignedShort();
        out.writeShort(fields);
        for (int i = 0; i < fields; i++) {
            member(in, out, utf8, false, n);
        }
        int methods = in.readUnsignedShort();
        out.writeShort(methods);
        boolean replaced = false;
        for (int i = 0; i < methods; i++) {
            replaced |= member(in, out, utf8, true, n);
        }
        if (!replaced) {
            throw new IOException("GLFW has no glfwInit()Z with code");
        }
        byte[] rest = new byte[in.available()];
        in.readFully(rest);
        out.write(rest);
        out.flush();
        return buffer.toByteArray();
    }

    static boolean member(DataInputStream in, DataOutputStream out, String[] utf8, boolean method, int n) throws IOException {
        out.writeShort(in.readUnsignedShort());
        int nameIndex = in.readUnsignedShort();
        out.writeShort(nameIndex);
        int descriptorIndex = in.readUnsignedShort();
        out.writeShort(descriptorIndex);
        boolean target = method && "glfwInit".equals(utf8[nameIndex]) && "()Z".equals(utf8[descriptorIndex]);
        boolean replaced = false;
        int attributes = in.readUnsignedShort();
        out.writeShort(attributes);
        for (int a = 0; a < attributes; a++) {
            int attributeName = in.readUnsignedShort();
            out.writeShort(attributeName);
            byte[] body = new byte[in.readInt()];
            in.readFully(body);
            if (target && "Code".equals(utf8[attributeName])) {
                body = callCode(n);
                replaced = true;
            }
            out.writeInt(body.length);
            out.write(body);
        }
        return replaced;
    }

    /** A Code attribute's body: the call through the constants added at n, then return false. */
    static byte[] callCode(int n) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream code = new DataOutputStream(buffer);
        code.writeShort(2); // max_stack
        code.writeShort(0); // max_locals: a static method with no parameters
        code.writeInt(19);
        code.writeByte(0xb8); // invokestatic System.getProperties
        code.writeShort(n + 5);
        code.writeByte(0x13); // ldc_w KEY
        code.writeShort(n + 7);
        code.writeByte(0xb6); // invokevirtual Properties.get
        code.writeShort(n + 13);
        code.writeByte(0xc0); // checkcast Runnable
        code.writeShort(n + 15);
        code.writeByte(0xb9); // invokeinterface Runnable.run
        code.writeShort(n + 19);
        code.writeByte(1);
        code.writeByte(0);
        code.writeByte(0x03); // iconst_0
        code.writeByte(0xac); // ireturn
        code.writeShort(0); // no exception table
        code.writeShort(0); // no attributes
        code.flush();
        return buffer.toByteArray();
    }

    static void utf8(DataOutputStream out, String text) throws IOException {
        out.writeByte(1);
        out.writeUTF(text);
    }

    static void classRef(DataOutputStream out, int name) throws IOException {
        out.writeByte(7);
        out.writeShort(name);
    }

    static void nameAndType(DataOutputStream out, int name, int descriptor) throws IOException {
        out.writeByte(12);
        out.writeShort(name);
        out.writeShort(descriptor);
    }

    static void memberRef(DataOutputStream out, int tag, int owner, int nameAndType) throws IOException {
        out.writeByte(tag);
        out.writeShort(owner);
        out.writeShort(nameAndType);
    }
}
