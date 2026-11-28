import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * 1.19.4's keyboard navigation written on 1.19.3 (knowledge/standins/1.19.4-1.19.3/), run as the stand-in files read,
 * against classes shaped as 1.19.3's: a screen holding a search field (y 22) above a key list (y 48 to 200) with one
 * entry, as Controlling's key screen does. 1.19.4 answers the list's rectangle from its own bounds, so an arrow down from
 * the field reaches the list's entry, and getRectangle hands the list's bounds back. A list answered as an empty
 * rectangle is never reached.
 *
 * The fake game's members are public: the port compiles stand-ins against stubs that open every member, and opens the
 * closed ones the stand-ins use through its access widener when it carries them.
 */
final class JarPortNavStandinTest {

    static final Path STANDINS = Path.of("knowledge/standins/1.19.4-1.19.3/net/minecraft/client/gui");

    static final Map<String, String> GAME = Map.of(
        "it/unimi/dsi/fastutil/ints/IntComparator.java", """
            package it.unimi.dsi.fastutil.ints;
            @SuppressWarnings("rawtypes")
            public interface IntComparator extends java.util.Comparator {
                int compare(int a, int b);
                int compare(Integer a, Integer b);
            }
            """,
        "net/minecraft/client/gui/components/events/GuiEventListener.java", """
            package net.minecraft.client.gui.components.events;
            public interface GuiEventListener {
            }
            """,
        "net/minecraft/client/gui/components/events/ContainerEventHandler.java", """
            package net.minecraft.client.gui.components.events;
            @SuppressWarnings("rawtypes")
            public interface ContainerEventHandler extends GuiEventListener {
                java.util.List children();
                GuiEventListener getFocused();
                void setFocused(GuiEventListener focused);
            }
            """,
        "net/minecraft/client/gui/components/AbstractWidget.java", """
            package net.minecraft.client.gui.components;
            public abstract class AbstractWidget implements net.minecraft.client.gui.components.events.GuiEventListener {
                public boolean active = true;
                public boolean visible = true;
                public int x, y, width, height;
                public boolean focused;
                public int getX() { return x; }
                public int getY() { return y; }
                public int getWidth() { return width; }
                public int getHeight() { return height; }
                public boolean isFocused() { return focused; }
                public void setFocused(boolean focused) { this.focused = focused; }
            }
            """,
        "net/minecraft/client/gui/components/AbstractSelectionList.java", """
            package net.minecraft.client.gui.components;
            import net.minecraft.client.gui.components.events.GuiEventListener;
            @SuppressWarnings("rawtypes")
            public abstract class AbstractSelectionList implements net.minecraft.client.gui.components.events.ContainerEventHandler {
                public int x0, y0, x1, y1;
                public final java.util.List entries = new java.util.ArrayList();
                GuiEventListener focused;
                public java.util.List children() { return entries; }
                public GuiEventListener getFocused() { return focused; }
                public void setFocused(GuiEventListener focused) { this.focused = focused; }
            }
            """,
        "net/minecraft/client/gui/screens/Screen.java", """
            package net.minecraft.client.gui.screens;
            import net.minecraft.client.gui.components.events.GuiEventListener;
            @SuppressWarnings("rawtypes")
            public abstract class Screen implements net.minecraft.client.gui.components.events.ContainerEventHandler {
                public int width = 400, height = 240;
                public final java.util.List children = new java.util.ArrayList();
                GuiEventListener focused;
                public java.util.List children() { return children; }
                public GuiEventListener getFocused() { return focused; }
                public void setFocused(GuiEventListener focused) { this.focused = focused; }
            }
            """,
        "keys/Setup.java", """
            package keys;
            import mmt.standin.net.minecraft.client.gui.ComponentPath;
            import mmt.standin.net.minecraft.client.gui.components.events.GuiEventListener;
            import mmt.standin.net.minecraft.client.gui.navigation.*;
            import net.minecraft.client.gui.components.AbstractSelectionList;
            import net.minecraft.client.gui.components.AbstractWidget;
            import net.minecraft.client.gui.screens.Screen;
            public final class Setup {
                static final class Field extends AbstractWidget {}
                static final class Entry extends AbstractWidget {}
                static final class Keys extends AbstractSelectionList {}
                static final class KeyScreen extends Screen {}
                @SuppressWarnings("unchecked")
                public static String run() {
                    Field search = new Field();
                    search.x = 100; search.y = 22; search.width = 200; search.height = 20;
                    Keys list = new Keys();
                    list.x0 = 0; list.y0 = 48; list.x1 = 400; list.y1 = 200;
                    Entry entry = new Entry();
                    entry.x = 100; entry.y = 50; entry.width = 200; entry.height = 20;
                    list.entries.add(entry);
                    KeyScreen screen = new KeyScreen();
                    screen.children.add(list);
                    screen.children.add(search);
                    screen.setFocused(search);
                    search.focused = true;
                    ScreenRectangle r = GuiEventListener.getRectangle(list);
                    ComponentPath down = GuiEventListener.nextFocusPath(screen, new FocusNavigationEvent$ArrowNavigation(ScreenDirection.DOWN));
                    String reached = "none";
                    for (ComponentPath p = down; p != null; p = p instanceof mmt.standin.net.minecraft.client.gui.ComponentPath$Path q ? q.childPath() : null) {
                        reached = p.component() == entry ? "entry" : p.component() == list ? "list" : p.component() == search ? "search" : "other";
                    }
                    return r.left() + "," + r.top() + "," + r.width() + "," + r.height() + " " + reached;
                }
            }
            """);

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        Path src = scratch.resolve("src"), out = scratch.resolve("classes");
        List<String> files = new ArrayList<>();
        for (var e : GAME.entrySet()) {
            Path f = src.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
            files.add(f.toString());
        }
        for (String stand : List.of("ComponentPath.java", "ComponentPath$Leaf.java", "ComponentPath$Path.java", "components/events/GuiEventListener.java")) {
            files.add(STANDINS.resolve(stand).toString());
        }
        try (var all = Files.list(STANDINS.resolve("navigation"))) {
            all.forEach(f -> files.add(f.toString()));
        }
        Files.createDirectories(out);
        List<String> command = new ArrayList<>(List.of("-nowarn", "-encoding", "UTF-8", "-d", out.toString()));
        command.addAll(files);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int compiled = javac.run(null, null, null, command.toArray(String[]::new));
        JarPortTest.expect(compiled == 0, "the navigation stand-ins compile against classes shaped as 1.19.3's");
        if (compiled == 0) {
            try (URLClassLoader loader = new URLClassLoader(new URL[] { out.toUri().toURL() }, JarPortNavStandinTest.class.getClassLoader())) {
                Method run = loader.loadClass("keys.Setup").getMethod("run");
                String got = (String) run.invoke(null);
                JarPortTest.expect(got.startsWith("0,48,400,152 "), "a key list's rectangle is its own bounds (x0, y0, x1 - x0, y1 - y0), as 1.19.4's AbstractSelectionList answers: " + got);
                JarPortTest.expect(got.endsWith(" entry"), "an arrow down from the search field reaches the key list's entry below it: " + got);
            }
        }
        JarPortTest.finish("JarPortNavStandinTest");
    }
}
