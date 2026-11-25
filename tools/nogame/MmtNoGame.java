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
 * - a server, when the thread named "Server thread" defines its first class or writes its first line. The game starts
 *   that thread once Main has loaded the data packs, and the thread's first work is to open the port and the world.
 *   That name is the game's own on every release.
 * - a client, when the game starts its window library, the first step of making its window (GLFW.glfwInit(), and
 *   SDL_Init on the releases that make their window with SDL): Bootstrap and (on Fabric) the mods' client start code
 *   have run. The call is replaced, in the one class as it loads, by a call back here. A window library this agent
 *   does not know would get past that, so the check also ends when the game binds the graphics library it draws with.
 *
 * Mixin applies a mixin when its target class loads, and a start only loads some of the game. So as the game's own
 * main begins (Mixin has read every config by then), MmtMixinCheck loads every class the configs target: a mixin the
 * game would only reach in a world is applied by Mixin here. The first class Mixin refuses ends the JVM there, a few
 * seconds in, as it ends the game; with none, every mixin is in place and the start goes on as the game's own.
 * The entry class gets that call as it loads: its main is kept under another name, and the main the loader calls
 * makes the call and then calls it.
 *
 * Usage: -javaagent:mmt-nogame.jar=server (or =client; ",mixins" ends the start once the mixins are read), with
 * -Dmmt.mixincheck.out naming the file of events: Mixin's (MmtMixinCheck's), and "stop" with why the JVM ended. A start
 * that ends without one ended by itself: it failed.
 * Java 8 syntax, no dependencies: it runs on whichever Java the release does.
 */
public final class MmtNoGame implements ClassFileTransformer, Runnable {

    static final long START = System.nanoTime();
    static final AtomicBoolean STOPPED = new AtomicBoolean();
    static final String SERVER_THREAD = "Server thread";
    /**
     * Where a window begins, by window library: a class and the names of its static methods that start the library or
     * make a window, whatever their parameters. The game starts the library first; making a window is named too, as SDL
     * starts itself when a window is asked for.
     */
    static final String[][] WINDOW_CALLS = {
        { "org/lwjgl/glfw/GLFW", "glfwInit", "glfwCreateWindow", "nglfwCreateWindow" },
        { "org/lwjgl/sdl/SDLInit", "SDL_Init", "SDL_InitSubSystem" },
        { "org/lwjgl/sdl/SDLVideo", "SDL_CreateWindow", "nSDL_CreateWindow", "SDL_CreateWindowWithProperties", "SDL_CreatePopupWindow" },
    };
    /**
     * What only a game with a window does: it binds OpenGL to the window's context, or makes a Vulkan device to draw
     * with. (Loading these libraries is no sign: 26.1 on, the game's main loads Vulkan's before anything else.)
     */
    static final String[][] GRAPHICS_CALLS = {
        { "org/lwjgl/opengl/GL", "createCapabilities" },
        { "org/lwjgl/vulkan/VK10", "vkCreateDevice", "nvkCreateDevice" },
    };
    static final String GRAPHICS_KEY = "mmt.nogame.graphics";
    /** The game's entry classes: the dedicated server's main (MinecraftServer's own before 1.16) and the client's. */
    static final String SERVER_MAIN = "net/minecraft/server/Main";
    static final String OLD_SERVER_MAIN = "net/minecraft/server/MinecraftServer";
    static final String CLIENT_MAIN = "net/minecraft/client/main/Main";
    static final String MAIN = "main";
    static final String MAIN_TYPE = "([Ljava/lang/String;)V";
    static final String KEPT_MAIN = "mmt$main";
    static final AtomicBoolean ENTRY_SEEN = new AtomicBoolean();
    static final AtomicBoolean MIXINS_READ = new AtomicBoolean();
    static volatile Thread stopping;
    /**
     * The system properties the calls written into the game's classes read their Runnable from (java.base is all a class
     * in any module can name): what glfwInit calls instead of making a window, and what the entry class's main calls first.
     */
    static final String KEY = "mmt.nogame.stop";
    static final String MAIN_KEY = "mmt.nogame.main";
    static String side = "server";
    /** Asked for with "SIDE,mixins": the check ends once the mixins are read, for a start that is only asked whether they apply. */
    static boolean mixinsOnly;
    static final String MIXINS_STOP = "the mixins were read";
    /** The loader that defined the game's entry class: the one the mixin targets are loaded through. */
    static volatile ClassLoader game;
    static volatile String entryClass;

    public static void premain(String arguments, Instrumentation instrumentation) {
        List<String> asked = Arrays.asList(String.valueOf(arguments).split(","));
        side = asked.contains("client") ? "client" : "server";
        mixinsOnly = asked.contains("mixins");
        MmtNoGame agent = new MmtNoGame();
        if (side.equals("client")) {
            System.getProperties().put(KEY, agent);
            System.getProperties().put(GRAPHICS_KEY, new Runnable() {
                @Override
                public void run() {
                    MmtMixinCheck.emit("note", "message", "The game went on to its graphics library without starting a window library this check knows.");
                    stop("the game asked for its graphics library");
                }
            });
        }
        System.getProperties().put(MAIN_KEY, new Runnable() {
            @Override
            public void run() {
                atMain();
            }
        });
        instrumentation.addTransformer(agent);
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
        if (entry && loader != null && ENTRY_SEEN.compareAndSet(false, true)) {
            game = loader;
            entryClass = name.replace('/', '.');
            try {
                return withCall(bytes, MAIN_KEY, new String[] { name, MAIN }, false);
            } catch (Throwable failure) {
                MmtMixinCheck.emit("note", "message", "Could not put the mixin check in front of " + name + "'s main, so the check ends here: " + failure);
                stop("the game's entry class could not be given the mixin check");
            }
        }
        if (!side.equals("client") || name == null) return null;
        for (String[] calls : WINDOW_CALLS) {
            if (!calls[0].equals(name)) continue;
            try {
                return withCall(bytes, KEY, calls, true);
            } catch (Throwable failure) {
                // A window must never open here: without the replaced call the check ends where it is.
                MmtMixinCheck.emit("note", "message", "Could not replace the start of " + name + ", so the check ends as it loads: " + failure);
                stop("the game loaded its window library");
            }
        }
        for (String[] calls : GRAPHICS_CALLS) {
            if (!calls[0].equals(name)) continue;
            try {
                return withCall(bytes, GRAPHICS_KEY, calls, true);
            } catch (Throwable failure) {
                MmtMixinCheck.emit("note", "message", "Could not replace the start of " + name + ", so the check ends as it loads: " + failure);
                stop("the game loaded its graphics library");
            }
        }
        return null;
    }

    /** What the replaced glfwInit calls. */
    @Override
    public void run() {
        stop("the game asked for its window");
    }

    /** What the entry class's main calls first: every mixin is applied before the game's own start-up code runs. */
    static void atMain() {
        if (!MIXINS_READ.compareAndSet(false, true)) return;
        readMixins(game, entryClass);
        if (mixinsOnly) stop(MIXINS_STOP);
    }

    /**
     * Every mixin target loaded through the game's loader (entry is the game's entry class, loaded already). Ends the
     * JVM at the first class Mixin refuses with an error the game stops on.
     */
    static void readMixins(ClassLoader game, String entry) {
        int refused;
        // Mixin's error handler is a class of Mixin's own loader: a system property is all this agent shares with it.
        System.setProperty("mmt.mixincheck.asTheGame", "true");
        try {
            refused = MmtMixinCheck.check(game, side.toUpperCase(Locale.ROOT), START, entry);
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
            MmtMixinCheck.emit("note", "message", "The game's main never ran, so no mixins were read.");
        }
        MmtMixinCheck.emit("stop", "reason", reason, "side", side, "seconds", MmtMixinCheck.seconds(START));
        Runtime.getRuntime().halt(0);
    }

    /**
     * The class file with a call of ((Runnable) System.getProperties().get(key)).run() in its static methods calls
     * (the class's name, then the methods' names). instead: the call is the whole body of every method of those names,
     * which then returns nothing (a window library's start). Otherwise it comes first, for main(String[]): the method
     * keeps its code under the name KEPT_MAIN, and a new method of its name makes the call and then calls it.
     * Written on the bytes, with no library: the constants are added at the end of the pool, and the code written is
     * straight-line, which needs no stack map.
     */
    static byte[] withCall(byte[] bytes, String key, String[] calls, boolean instead) throws IOException {
        String name = calls[1];
        String type = MAIN_TYPE;
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(bytes.length + 512);
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(in.readInt());
        out.writeShort(in.readUnsignedShort());
        out.writeShort(in.readUnsignedShort());
        int count = in.readUnsignedShort();
        out.writeShort(count + 26);
        String[] utf8 = MmtNeoForgeTarget.constants(in, out, count, null, null);
        int access = in.readUnsignedShort();
        int thisClass = in.readUnsignedShort();
        int n = count;
        utf8(out, "java/lang/System");
        classRef(out, n);
        utf8(out, "getProperties");
        utf8(out, "()Ljava/util/Properties;");
        nameAndType(out, n + 2, n + 3);
        memberRef(out, 10, n + 1, n + 4);
        utf8(out, key);
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
        // For the method kept under another name: its new name, the name and type of the one written in its place, and the call of it.
        utf8(out, KEPT_MAIN);
        utf8(out, name);
        utf8(out, type);
        utf8(out, "Code");
        nameAndType(out, n + 20, n + 22);
        memberRef(out, 10, thisClass, n + 24);
        out.writeShort(access);
        out.writeShort(thisClass);
        out.writeShort(in.readUnsignedShort());
        int interfaces = in.readUnsignedShort();
        out.writeShort(interfaces);
        for (int i = 0; i < interfaces; i++) {
            out.writeShort(in.readUnsignedShort());
        }
        int fields = in.readUnsignedShort();
        out.writeShort(fields);
        for (int i = 0; i < fields; i++) {
            member(in, out, utf8, null, false, n);
        }
        int methods = in.readUnsignedShort();
        out.writeShort(instead ? methods : methods + 1);
        boolean found = false;
        for (int i = 0; i < methods; i++) {
            found |= member(in, out, utf8, calls, instead, n);
        }
        if (!found) {
            throw new IOException("no static " + (instead ? Arrays.asList(calls).subList(1, calls.length).toString() : name + type) + " with code");
        }
        if (!instead) {
            out.writeShort(0x0009); // public static
            out.writeShort(n + 21);
            out.writeShort(n + 22);
            out.writeShort(1);
            out.writeShort(n + 23);
            byte[] code = callCode(n, false, type);
            out.writeInt(code.length);
            out.write(code);
        }
        byte[] rest = new byte[in.available()];
        in.readFully(rest);
        out.write(rest);
        out.flush();
        return buffer.toByteArray();
    }

    /**
     * Copies one field or method. The methods of calls (null for a field; withCall says how it reads) are the ones the
     * call goes into: the body is replaced by the call (instead), or the method is renamed to KEPT_MAIN and kept whole.
     * Returns whether this was one of them.
     */
    static boolean member(DataInputStream in, DataOutputStream out, String[] utf8, String[] calls, boolean instead, int n) throws IOException {
        int access = in.readUnsignedShort();
        out.writeShort(access);
        int nameIndex = in.readUnsignedShort();
        int descriptorIndex = in.readUnsignedShort();
        boolean target = false;
        for (int c = 1; calls != null && c < calls.length; c++) {
            target |= (access & 0x0008) != 0 && calls[c].equals(utf8[nameIndex]) && (instead || MAIN_TYPE.equals(utf8[descriptorIndex]));
        }
        out.writeShort(target && !instead ? n + 20 : nameIndex);
        out.writeShort(descriptorIndex);
        boolean found = false;
        int attributes = in.readUnsignedShort();
        out.writeShort(attributes);
        for (int a = 0; a < attributes; a++) {
            int attributeName = in.readUnsignedShort();
            out.writeShort(attributeName);
            byte[] body = new byte[in.readInt()];
            in.readFully(body);
            if (target && "Code".equals(utf8[attributeName])) {
                if (instead) body = callCode(n, true, utf8[descriptorIndex]);
                found = true;
            }
            out.writeInt(body.length);
            out.write(body);
        }
        return found;
    }

    /**
     * A Code attribute's body: the call through the constants added at n, then a return of nothing in the method's own
     * type (instead), or the call of the kept method with this one's one argument and return. type: the method's.
     */
    static byte[] callCode(int n, boolean instead, String type) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream code = new DataOutputStream(buffer);
        code.writeShort(2); // max_stack
        code.writeShort(parameterSlots(type)); // max_locals: the parameters of a static method
        code.writeInt(instead ? 19 : 22);
        code.writeByte(0xb8); // invokestatic System.getProperties
        code.writeShort(n + 5);
        code.writeByte(0x13); // ldc_w the key
        code.writeShort(n + 7);
        code.writeByte(0xb6); // invokevirtual Properties.get
        code.writeShort(n + 13);
        code.writeByte(0xc0); // checkcast Runnable
        code.writeShort(n + 15);
        code.writeByte(0xb9); // invokeinterface Runnable.run
        code.writeShort(n + 19);
        code.writeByte(1);
        code.writeByte(0);
        if (instead) {
            char answers = type.charAt(type.indexOf(')') + 1);
            // The call above never comes back; what follows only has to be a valid end for the method's type.
            int[] end = answers == 'V' ? new int[] { 0x00, 0xb1 } // nop, return
                : answers == 'J' ? new int[] { 0x09, 0xad } // lconst_0, lreturn
                : answers == 'F' ? new int[] { 0x0b, 0xae } // fconst_0, freturn
                : answers == 'D' ? new int[] { 0x0e, 0xaf } // dconst_0, dreturn
                : answers == 'L' || answers == '[' ? new int[] { 0x01, 0xb0 } // aconst_null, areturn
                : new int[] { 0x03, 0xac }; // iconst_0, ireturn
            code.writeByte(end[0]);
            code.writeByte(end[1]);
        } else {
            code.writeByte(0x2a); // aload_0
            code.writeByte(0xb8); // invokestatic the kept method
            code.writeShort(n + 25);
            code.writeByte(0xb1); // return
        }
        code.writeShort(0); // no exception table
        code.writeShort(0); // no attributes
        code.flush();
        return buffer.toByteArray();
    }

    /** The local variable slots a static method's parameters take: two for a long or a double, one for anything else. */
    static int parameterSlots(String type) {
        int slots = 0;
        for (int i = 1; type.charAt(i) != ')'; i++) {
            char c = type.charAt(i);
            slots += c == 'J' || c == 'D' ? 2 : 1;
            while (type.charAt(i) == '[') i++;
            if (type.charAt(i) == 'L') i = type.indexOf(';', i);
        }
        return slots;
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
