package mmt.standin.net.neoforged.fml;

import java.io.Serializable;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * NeoForge 20.6's DistExecutor (FancyModLoader 3.0.45) on 21.0 (FancyModLoader 4.0.23), which drops the class.
 *
 * Read from both loaders' files: 3.0.45's DistExecutor, every method marked @Deprecated, ran the inner supplier's value
 * when FMLEnvironment.dist was the side asked for (runWhenOn, callWhenOn and their unsafe and safe forms; the safe ones
 * only checked in development that the supplier was a serializable lambda, which a ported mod in production skips), and
 * picked the client or server supplier by FMLEnvironment.dist (runForDist), refusing any other side with
 * IllegalArgumentException("UNSIDED?"). 4.0.23 keeps FMLEnvironment.dist and Dist (mergetool's api jar) unchanged.
 *
 * Loader files only: NeoForge's game primer covers vanilla classes, not FancyModLoader.
 */
public final class DistExecutor {

    private DistExecutor() {}

    public static Object callWhenOn(Dist dist, Supplier toRun) {
        return unsafeCallWhenOn(dist, toRun);
    }

    public static Object unsafeCallWhenOn(Dist dist, Supplier toRun) {
        if (dist != FMLEnvironment.dist) {
            return null;
        }
        try {
            return ((Callable) toRun.get()).call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Object safeCallWhenOn(Dist dist, Supplier toRun) {
        return unsafeCallWhenOn(dist, toRun);
    }

    public static void runWhenOn(Dist dist, Supplier toRun) {
        unsafeRunWhenOn(dist, toRun);
    }

    public static void unsafeRunWhenOn(Dist dist, Supplier toRun) {
        if (dist == FMLEnvironment.dist) {
            ((Runnable) toRun.get()).run();
        }
    }

    public static void safeRunWhenOn(Dist dist, Supplier toRun) {
        unsafeRunWhenOn(dist, toRun);
    }

    public static Object runForDist(Supplier clientTarget, Supplier serverTarget) {
        return unsafeRunForDist(clientTarget, serverTarget);
    }

    public static Object unsafeRunForDist(Supplier clientTarget, Supplier serverTarget) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            return ((Supplier) clientTarget.get()).get();
        }
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            return ((Supplier) serverTarget.get()).get();
        }
        throw new IllegalArgumentException("UNSIDED?");
    }

    public static Object safeRunForDist(Supplier clientTarget, Supplier serverTarget) {
        return unsafeRunForDist(clientTarget, serverTarget);
    }

    public interface SafeReferent {}

    public interface SafeCallable extends SafeReferent, Callable, Serializable {}

    public interface SafeSupplier extends SafeReferent, Supplier, Serializable {}

    public interface SafeRunnable extends SafeReferent, Runnable, Serializable {}
}
