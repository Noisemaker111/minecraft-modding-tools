import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * The 1.21.1 -> 1.21.2 stand-in for Entity.teleportTo(ServerLevel, x, y, z, Set<RelativeMovement>, yRot, xRot), run on
 * this JVM. 1.21.1 always moved to the absolute target; 1.21.2's teleportTo adds the current value on every axis its
 * Set<Relative> names (PositionMoveRotation.calculateAbsolute) and keeps the velocity only on a named DELTA axis. The
 * checks need no game: the real stand-in sources (knowledge/standins/1.21.1-1.21.2/net/minecraft/world/entity/Entity.java
 * and RelativeMovement.java) are compiled against small classes under the game's names whose teleport is 1.21.2's own
 * code (Entity.teleportTo, teleportSetPosition, PositionMoveRotation.calculateAbsolute and ServerPlayer.teleportTo's head,
 * copied from the decompiled 1.21.2 jar), and each case starts away from its target with a velocity, so passing the old
 * relatives through lands on the sum and a lost DELTA zeroes the velocity.
 */
final class JarPortStandinTeleportTest {

    static final String STANDINS = "knowledge/standins/1.21.1-1.21.2/net/minecraft/world/entity/";
    static int failures;

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        Path src = scratch.resolve("src");
        write(src.resolve("net/minecraft/world/entity/Relative.java"), """
            package net.minecraft.world.entity;
            public enum Relative { X, Y, Z, Y_ROT, X_ROT, DELTA_X, DELTA_Y, DELTA_Z, ROTATE_DELTA }
            """);
        write(src.resolve("net/minecraft/world/level/Level.java"), """
            package net.minecraft.world.level;
            public class Level {
                public Object getServer() { return null; }
            }
            """);
        write(src.resolve("net/minecraft/server/level/ServerLevel.java"), """
            package net.minecraft.server.level;
            public class ServerLevel extends net.minecraft.world.level.Level {}
            """);
        write(src.resolve("net/minecraft/commands/CommandSource.java"), """
            package net.minecraft.commands;
            public interface CommandSource { CommandSource NULL = null; }
            """);
        write(src.resolve("net/minecraft/commands/CommandSourceStack.java"), """
            package net.minecraft.commands;
            public class CommandSourceStack {
                public CommandSourceStack(CommandSource a, Object b, Object c, Object d, int e, String f, Object g, Object h, Object i) {}
            }
            """);
        // 1.21.2's Entity.teleportTo -> teleport(TeleportTransition) -> teleportSetPosition(calculateAbsolute), with the
        // transition's velocity Vec3.ZERO; a level change makes the entity anew from this one (restoreFrom), as 1.21.2 does.
        write(src.resolve("net/minecraft/world/entity/Entity.java"), """
            package net.minecraft.world.entity;
            import java.util.Set;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.level.Level;
            public class Entity {
                public Level level;
                public double x, y, z, dx, dy, dz;
                public float yRot, xRot, yHeadRot;
                public Level level() { return level; }
                public Object position() { return null; }
                public Object getRotationVector() { return null; }
                public Name getName() { return new Name(); }
                public Object getDisplayName() { return null; }
                public void setYHeadRot(float rot) { yHeadRot = rot; }
                public static class Name { public String getString() { return ""; } }
                public boolean teleportTo(ServerLevel level, double x, double y, double z, Set<Relative> rel, float yRot, float xRot, boolean camera) {
                    float pitch = Math.max(-90.0F, Math.min(90.0F, xRot));
                    this.level = level;
                    double nx = (rel.contains(Relative.X) ? this.x : 0.0) + x;
                    double ny = (rel.contains(Relative.Y) ? this.y : 0.0) + y;
                    double nz = (rel.contains(Relative.Z) ? this.z : 0.0) + z;
                    float ny2 = (rel.contains(Relative.Y_ROT) ? this.yRot : 0.0F) + yRot;
                    float nx2 = (rel.contains(Relative.X_ROT) ? this.xRot : 0.0F) + pitch;
                    this.dx = rel.contains(Relative.DELTA_X) ? this.dx : 0.0;
                    this.dy = rel.contains(Relative.DELTA_Y) ? this.dy : 0.0;
                    this.dz = rel.contains(Relative.DELTA_Z) ? this.dz : 0.0;
                    this.x = nx;
                    this.y = ny;
                    this.z = nz;
                    this.yRot = ny2;
                    this.xRot = nx2;
                    this.yHeadRot = ny2;
                    return true;
                }
            }
            """);
        // 1.21.2's ServerPlayer.teleportTo: camera when asked, then the head from the set.
        write(src.resolve("net/minecraft/server/level/ServerPlayer.java"), """
            package net.minecraft.server.level;
            import java.util.Set;
            import net.minecraft.world.entity.Relative;
            public class ServerPlayer extends net.minecraft.world.entity.Entity {
                public boolean camera;
                public net.minecraft.commands.CommandSourceStack createCommandSourceStack() { return null; }
                @Override
                public boolean teleportTo(ServerLevel level, double x, double y, double z, Set<Relative> rel, float yRot, float xRot, boolean camera) {
                    float head = this.yHeadRot;
                    this.camera = camera;
                    boolean moved = super.teleportTo(level, x, y, z, rel, yRot, xRot, camera);
                    if (moved) {
                        this.setYHeadRot(rel.contains(Relative.Y_ROT) ? head + yRot : yRot);
                    }
                    return moved;
                }
            }
            """);
        List<String> files = new ArrayList<>();
        try (var walk = Files.walk(src)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> files.add(p.toString()));
        }
        files.add(Path.of(STANDINS, "Entity.java").toString());
        files.add(Path.of(STANDINS, "RelativeMovement.java").toString());
        Path out = Files.createDirectories(scratch.resolve("classes"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        List<String> javacArgs = new ArrayList<>(List.of("-nowarn", "-proc:none", "-encoding", "UTF-8", "-d", out.toString()));
        javacArgs.addAll(files);
        if (javac.run(null, null, null, javacArgs.toArray(String[]::new)) != 0) {
            throw new IllegalStateException("the stand-ins do not compile against the teleport classes");
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[] {out.toUri().toURL()}, JarPortStandinTeleportTest.class.getClassLoader())) {
            Class<?> entity = loader.loadClass("net.minecraft.world.entity.Entity");
            Class<?> player = loader.loadClass("net.minecraft.server.level.ServerPlayer");
            Class<?> serverLevel = loader.loadClass("net.minecraft.server.level.ServerLevel");
            Class<?> relativeMovement = loader.loadClass("mmt.standin.net.minecraft.world.entity.RelativeMovement");
            Method teleport = loader.loadClass("mmt.standin.net.minecraft.world.entity.Entity").getMethod("teleportTo",
                entity, serverLevel, double.class, double.class, double.class, Set.class, float.class, float.class);
            Object here = serverLevel.getConstructor().newInstance();
            Object there = serverLevel.getConstructor().newInstance();
            Set<?> all = (Set<?>) relativeMovement.getField("ALL").get(null);

            // Another entity, the old ALL: the target is absolute and the velocity stays, as 1.21.1's moveTo left it.
            Object mob = start(entity, here);
            teleport.invoke(null, mob, here, 20.0, 70.0, 10.0, all, 30.0F, 15.0F);
            expect("entity, every axis named", mob, 20, 70, 10, 30, 15, 0.5, -0.1, 0.25);
            expect("entity's head", entity.getField("yHeadRot").getFloat(mob), 30.0F);

            // A player in its level, X named: absolute target, velocity kept on X only (the client's 1.21.1 handling).
            Object steve = start(player, here);
            teleport.invoke(null, steve, here, 20.0, 70.0, 10.0, oneOf(relativeMovement, "X"), 30.0F, 15.0F);
            expect("player, X named", steve, 20, 70, 10, 30, 15, 0.5, 0.0, 0.0);
            expect("player keeps its camera in its level", player.getField("camera").getBoolean(steve), false);

            // A player in its level, rotation named: the angles are still absolute, the head too.
            Object alex = start(player, here);
            teleport.invoke(null, alex, here, 20.0, 70.0, 10.0, oneOf(relativeMovement, "Y_ROT"), 30.0F, 15.0F);
            expect("player, Y_ROT named", alex, 20, 70, 10, 30, 15, 0.0, 0.0, 0.0);
            expect("player's head", entity.getField("yHeadRot").getFloat(alex), 30.0F);

            // A player to another level: absolute, no velocity, and its own camera again.
            Object far = start(player, here);
            teleport.invoke(null, far, there, 20.0, 70.0, 10.0, all, 30.0F, 15.0F);
            expect("player to another level", far, 20, 70, 10, 30, 15, 0.0, 0.0, 0.0);
            expect("player's camera reset on a level change", player.getField("camera").getBoolean(far), true);
        }
        if (failures > 0) {
            System.exit(1);
        }
        System.out.println("JarPortStandinTeleportTest: checks passed");
    }

    static Object start(Class<?> type, Object level) throws Exception {
        Object e = type.getConstructor().newInstance();
        Class<?> entity = type.getName().endsWith("Entity") ? type : type.getSuperclass();
        entity.getField("level").set(e, level);
        entity.getField("x").setDouble(e, 100.0);
        entity.getField("y").setDouble(e, 64.0);
        entity.getField("z").setDouble(e, -50.0);
        entity.getField("yRot").setFloat(e, 90.0F);
        entity.getField("xRot").setFloat(e, -20.0F);
        entity.getField("yHeadRot").setFloat(e, 80.0F);
        entity.getField("dx").setDouble(e, 0.5);
        entity.getField("dy").setDouble(e, -0.1);
        entity.getField("dz").setDouble(e, 0.25);
        return e;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Set<?> oneOf(Class<?> relativeMovement, String name) {
        return EnumSet.of(Enum.valueOf((Class) relativeMovement, name));
    }

    static void expect(String what, Object e, double x, double y, double z, float yRot, float xRot, double dx, double dy, double dz) throws Exception {
        Class<?> c = e.getClass().getName().endsWith("Entity") ? e.getClass() : e.getClass().getSuperclass();
        double[] got = {c.getField("x").getDouble(e), c.getField("y").getDouble(e), c.getField("z").getDouble(e), c.getField("yRot").getFloat(e),
            c.getField("xRot").getFloat(e), c.getField("dx").getDouble(e), c.getField("dy").getDouble(e), c.getField("dz").getDouble(e)};
        double[] want = {x, y, z, yRot, xRot, dx, dy, dz};
        for (int i = 0; i < want.length; i++) {
            if (Math.abs(got[i] - want[i]) > 1e-9) {
                fail(what + ": got " + java.util.Arrays.toString(got) + ", want " + java.util.Arrays.toString(want) + " (x y z yRot xRot dx dy dz)");
                return;
            }
        }
    }

    static void expect(String what, Object got, Object want) {
        if (!got.equals(want)) {
            fail(what + ": got " + got + ", want " + want);
        }
    }

    static void fail(String message) {
        failures++;
        System.out.println("FAIL " + message);
    }

    static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
