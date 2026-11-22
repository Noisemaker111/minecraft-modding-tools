package mmt.standin.net.neoforged.fml;

import java.util.function.BiPredicate;
import java.util.function.Supplier;
import net.neoforged.fml.IExtensionPoint;

/**
 * NeoForge 20.4's IExtensionPoint.DisplayTest (FancyModLoader 2.0.17) on 20.5 (20.5.21-beta, FancyModLoader 3.0.18).
 *
 * Read from both loaders' files: 2.0.17's DisplayTest was a record of the version a mod reports to the other side and
 * the test of the other side's version, registered with ModLoadingContext.registerExtensionPoint(DisplayTest.class,
 * supplier) and read by the network handshake. 3.0.18 removed it (IExtensionPoint is an empty marker interface and a
 * mod's display test is declared in its mods.toml); registerExtensionPoint(class, supplier) still keeps any extension
 * point. The record as it was, an extension point no 20.5 code reads: registering it no longer changes the handshake.
 *
 * Loader files only: NeoForge's game primer covers vanilla classes, not FancyModLoader.
 */
public record IExtensionPoint$DisplayTest(Supplier suppliedVersion, BiPredicate remoteVersionTest) implements IExtensionPoint {

    public static final String IGNORESERVERONLY = "OHNOES😱😱😱😱😱😱😱😱😱😱😱😱😱😱😱😱😱";
}
